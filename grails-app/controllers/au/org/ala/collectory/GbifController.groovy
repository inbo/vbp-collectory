package au.org.ala.collectory

import au.org.ala.collectory.resources.gbif.GbifRepatDataSourceAdapter
import au.org.ala.plugins.openapi.Path
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import grails.converters.JSON
import groovy.json.JsonSlurper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement

import javax.ws.rs.Produces

import static io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER
import static io.swagger.v3.oas.annotations.enums.ParameterIn.PATH
import static io.swagger.v3.oas.annotations.enums.ParameterIn.PATH
import static io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY
import static io.swagger.v3.oas.annotations.enums.ParameterIn.QUERY

class GbifController {
    static final API_KEY_COOKIE = "ALA-API-Key"

    def collectoryAuthService
    def gbifRegistryService
    def asyncGbifRegistryService
    def gbifService
    def gbifSyncService
    def authService
    def externalDataService
    def dataLinkService

    def healthCheck() {
        gbifRegistryService.generateSyncBreakdown()
    }

    def healthCheckLinked() {

        log.info("Starting report.....")
        def url = grailsApplication.config.biocacheServicesUrl + "/occurrences/search?q=*:*&facets=data_resource_uid&pageSize=0&facet=on&flimit=-1"
        def js = new JsonSlurper()
        def biocacheSearch = js.parse(new URL(url), "UTF-8")

        def dataResourcesWithData = [:]
        def shareable = [:]
        def licenceIssues = [:]
        def notShareable = [:]
        def providedByGBIF = [:]
        def linkedToDataProvider = [:]
        def linkedToInstitution = [:]

        biocacheSearch.facetResults[0].fieldResult.each { result ->
            def uid = result.fq.replaceAll("\"","").replaceAll("data_resource_uid:","")

            def isShareable = true

            //retrieve current licence
            def dataResource = DataResource.findByUid(uid)
            if(dataResource) {

                dataResourcesWithData[dataResource] = result.count

                //get the data provider if available...
                def linked = false

                if(dataResource.consumerInstitutions){
                    //we have an institution
                    linkedToInstitution[dataResource] = result.count
                    linked = true
                }

                if(!linked) {
                    def dataProvider = dataResource.getDataProvider()
                    if(!dataProvider){
                        isShareable = false //no institution and no data provider
                    } else {
                        linkedToDataProvider[dataResource] = result.count
                        linked = true
                    }
                }

                if(linked) {

                    if (dataResource.licenseType == null || !dataResource.licenseType.startsWith("CC")) {
                        licenceIssues[dataResource] = result.count
                        isShareable = false
                    }

                    if (!dataResource.isShareableWithGBIF) {
                        notShareable[dataResource] = result.count
                        isShareable = false
                    }

                    if (dataResource.gbifDataset) {
                        providedByGBIF[dataResource] = result.count
                        isShareable = false
                    }

                    if (isShareable) {
                        shareable[dataResource] = result.count
                    }
                }
            }
        }

        [
                dataResourcesWithData:dataResourcesWithData,
                shareable:shareable,
                licenceIssues:licenceIssues,
                notShareable:notShareable,
                providedByGBIF:providedByGBIF,
                linkedToDataProvider: linkedToDataProvider,
                linkedToInstitution: linkedToInstitution,
        ]

    }

    /**
     * Download CSV report of our ability to share resources with GBIF.
     *
     * @return
     */
    def downloadCSV() {
        response.setContentType("text/csv")
        response.setHeader("Content-disposition", "attachment;filename=gbif-healthcheck.csv")
        gbifRegistryService.writeCSVReportForGBIF(response.outputStream)
    }

    def syncAllResources(){
        log.info("Starting all sync resources...checking user has role ${grailsApplication.config.gbifRegistrationRole}")
        def errorMessage = ""

        try {
            if (authService.userInRole(grailsApplication.config.gbifRegistrationRole)){
                asyncGbifRegistryService.updateAllResources()
                        .onComplete {
                            log.info "Sync complete"
                        }
                        .onError { Throwable err ->
                            log.error("An error occured ${err.message}", err)
                        }
            } else {
                errorMessage = "User does not have sufficient privileges to perform this."

                log.error("Starting all sync resources..." + errorMessage)
            }
        } catch (Exception e){
            log.error(e.getMessage(), e)
        }

        [errorMessage: errorMessage]
    }

    @Operation(
            method = "GET",
            tags = "gbif",
            operationId = "scanGbif",
            summary = "Update the collectory with data from external resources GBIF",
            description = "Update the collectory with data from external resources i.e. GBIF",
            parameters = [
                    @Parameter(
                            name = "uid",
                            in = PATH,
                            description = "provider uid",
                            schema = @Schema(implementation = String),
                            example = "1",
                            required = true
                    ),
            ],
            responses = [
                    @ApiResponse(
                            description = "Result of the scan operation",
                            responseCode = "200",
                            content = [
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = GbifScanResponse)
                                    )
                            ],
                            headers = [
                                    @Header(name = 'Access-Control-Allow-Headers', description = "CORS header", schema = @Schema(type = "string")),
                                    @Header(name = 'Access-Control-Allow-Methods', description = "CORS header", schema = @Schema(type = "string")),
                                    @Header(name = 'Access-Control-Allow-Origin', description = "CORS header", schema = @Schema(type = "string"))
                            ]
                    )
            ],
            security = [@SecurityRequirement(name = 'openIdConnect')]
    )
    @Path("/ws/gbif/scan/{uid}")
    @Produces("application/json")
    def scan(){
        if (!params.uid || !params.uid.startsWith('dp')){
            response.sendError(400, "No valid UID supplied")
            return
        }

        DataProvider dataProvider = DataProvider.findByUid(params.uid)
        if (!dataProvider){
            response.sendError(404)
            return
        }

        def sync = gbifSyncService.startSync(dataProvider.resources.toList(), "data provider ${dataProvider.name} (${dataProvider.uid})")
        redirect(action: "syncStatus", params: [loadGuid: sync.loadGuid])
    }

    /**
     * Synchronises resources that are not attached to a data provider.
     * Pass uids=dr12,dr13 for specific resources, or orphans=true for every resource with a GBIF key and no provider.
     */
    def scanResources() {
        List resources
        if (params.uids) {
            List uids = params.uids.toString().split(',').collect { it.trim() }.findAll { it }
            resources = DataResource.findAllByUidInList(uids)
        } else if (params.orphans?.toString() == 'true') {
            resources = DataResource.findAllByDataProviderIsNull().findAll { it.guid }
        } else if (params.all?.toString() == 'true') {
            resources = DataResource.all.findAll { it.guid }
        } else {
            response.sendError(400, "Pass uids=<comma separated resource uids>, orphans=true or all=true")
            return
        }
        if (!resources) {
            response.sendError(404, "No matching resource found")
            return
        }
        def sync = gbifSyncService.startSync(resources, "${resources.size()} resource(s) without a data provider")
        redirect(action: "syncStatus", params: [loadGuid: sync.loadGuid])
    }

    /**
     * Page to choose which automatic update to launch: a data provider, the orphans, or selected resources.
     */
    def syncScan() {
        def providers = DataProvider.all.sort { it.name }
        def resources = DataResource.all.findAll { it.guid }.sort { it.name }
        def orphanCount = DataResource.findAllByDataProviderIsNull().count { it.guid }
        def allCount = resources.size()
        render(view: 'syncScan', model: [providers: providers, resources: resources, orphanCount: orphanCount, allCount: allCount])
    }

    /**
     * Forwards the choice made on the syncScan page to the matching scan address. The scan itself checks the login.
     */
    def launchScan() {
        switch (params.method) {
            case 'provider':
                if (!params.providerUid) {
                    flash.message = "Choose a data provider first."
                    redirect(action: 'syncScan')
                    return
                }
                redirect(uri: "/ws/gbif/scan/${params.providerUid}")
                break
            case 'orphans':
                redirect(uri: "/ws/gbif/scanResources?orphans=true")
                break
            case 'all':
                redirect(uri: "/ws/gbif/scanResources?all=true")
                break
            case 'resources':
                List uids = params.list('resourceUids').findAll { it }
                if (!uids) {
                    flash.message = "Select at least one data resource first."
                    redirect(action: 'syncScan')
                    return
                }
                redirect(uri: "/ws/gbif/scanResources?uids=" + uids.join(','))
                break
            default:
                flash.message = "Choose one of the three update methods."
                redirect(action: 'syncScan')
        }
    }

    /**
     * Shows the progress and results of a synchronisation run started by the scan
     */
    def syncStatus() {
        def run = gbifSyncService.status(params.loadGuid)
        if (!run) {
            response.sendError(404)
            return
        }
        if (params.format == 'json') {
            render(run as JSON)
            return
        }
        render(view: 'syncStatus', model: [run: run])
    }

    @JsonIgnoreProperties('metaClass')
    class GbifScanResponse {
        String loadGuid
        String trackingUrl
        ArrayList<Object> updates
        ArrayList<Object>  resources

    }
}
