package au.org.ala.collectory

import groovy.json.JsonOutput
import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import org.apache.http.HttpHeaders
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipFile

/**
 * First version of the automatic GBIF synchronisation.
 *
 * Only the resources of a data provider that GBIF has published after the collectory last updated them are reloaded.
 * Each one is reloaded with the region (or country) it was originally loaded with, so Flanders stays Flanders.
 * Every step is logged with the prefix [GBIF-SYNC].
 */
class GbifSyncService {

    def grailsApplication
    def gbifService

    static final int MAX_ATTEMPTS = 3
    static final long RETRY_WAIT_MS = 30000L
    static final long POLL_WAIT_MS = 60000L
    static final int MAX_POLLS = 720
    static final List END_STATUSES = ["SUCCEEDED", "CANCELLED", "FAILED", "KILLED", "SUSPENDED", "UNKNOWN"]

    def executor = Executors.newSingleThreadExecutor()
    def runs = new ConcurrentHashMap()

    /**
     * Starts a synchronisation run for the given resources. The plan is returned at once; downloads run in the background.
     */
    Map startSync(List<DataResource> resources, String label) {
        List plan = resources.collect { planResource(it) }
        String loadGuid = UUID.randomUUID().toString()
        runs[loadGuid] = [loadGuid: loadGuid, label: label, startedAt: new Date(), status: 'RUNNING', plan: plan, results: new ConcurrentHashMap()]
        plan.findAll { it.action != 'UPDATE' }.each { record(loadGuid, it.uid, it.action, it.reason) }
        executor.submit({ runSync(loadGuid, plan) } as Callable)
        log.info("[GBIF-SYNC] run ${loadGuid} started for ${label}: ${plan.count { it.action == 'UPDATE' }} to update")
        [loadGuid: loadGuid, plan: plan]
    }

    Map status(String loadGuid) {
        runs[loadGuid]
    }

    /**
     * Decides what happens to one resource. Nothing is downloaded here.
     */
    Map planResource(DataResource dr) {
        Map plan = [uid: dr.uid, name: dr.name, guid: dr.guid, lastUpdated: dr.lastUpdated,
                    region: dr.region, country: dr.repatriationCountry, action: 'REFUSE', reason: '', polygon: null]
        if (!dr.guid) {
            plan.reason = 'No GBIF key on this resource'
            return plan
        }
        Date pubDate = gbifService.getGbifDatasetLastUpdated(dr.guid)
        plan.pubDate = pubDate
        if (!pubDate) {
            plan.reason = 'GBIF publication date unavailable'
            return plan
        }
        if (dr.lastUpdated && !pubDate.after(dr.lastUpdated)) {
            plan.action = 'SKIP'
            plan.reason = 'Already up to date with GBIF'
            return plan
        }
        if (!dr.region && !dr.repatriationCountry) {
            plan.reason = 'No region and no country recorded: the original scope is unknown, refusing to download the whole dataset'
            return plan
        }
        plan.polygon = scopePolygon(dr.region, dr.repatriationCountry)
        if (!plan.polygon) {
            plan.reason = "No area polygon for ${dr.region ?: dr.repatriationCountry}: refusing to download without a filter"
            return plan
        }
        plan.action = 'UPDATE'
        plan.reason = "GBIF published ${pubDate} after the last update ${dr.lastUpdated}"
        plan
    }

    String scopePolygon(String region, String country) {
        def polygons = gbifService.getRegionPolygonMap()
        region ? polygons.get(region) : polygons.get(country)
    }

    void runSync(String loadGuid, List plan) {
        plan.findAll { it.action == 'UPDATE' }.each { Map item ->
            record(loadGuid, item.uid, 'RUNNING', "Requesting GBIF download for ${item.region ?: item.country}")
            try {
                record(loadGuid, item.uid, 'UPDATED', reloadResource(item))
            } catch (Exception e) {
                log.error("[GBIF-SYNC] ${item.uid} failed: ${e.message}", e)
                record(loadGuid, item.uid, 'FAILED', e.message)
            }
        }
        runs[loadGuid].status = 'COMPLETED'
        log.info("[GBIF-SYNC] run ${loadGuid} completed")
    }

    void record(String loadGuid, String uid, String status, String message) {
        runs[loadGuid].results[uid] = [status: status, message: message, time: new Date()]
        log.info("[GBIF-SYNC] ${uid}: ${status} ${message ?: ''}")
    }

    /**
     * Downloads the resource again with its original area, checks the download, and only then replaces the archive.
     */
    String reloadResource(Map item) {
        DataResource before = DataResource.withTransaction { DataResource.findByUid(item.uid) }
        String regionBefore = before.region

        String downloadId = gbifService.startGBIFDownload(item.guid, item.polygon)
        if (!downloadId) {
            throw new IllegalStateException("GBIF refused the download request (see the 'refused' line above)")
        }
        String status = waitForDownload(downloadId)
        if (status != 'SUCCEEDED') {
            throw new IllegalStateException("GBIF download ${downloadId} ended with status ${status}; nothing was changed")
        }

        Map download = retry { gbifService.getJSONWSWithAuth(grailsApplication.config.gbifApiUrl + "occurrence/download/" + downloadId, grailsApplication.config.gbifApiUser, grailsApplication.config.gbifApiPassword) }
        if (!download) {
            throw new IllegalStateException("Could not read GBIF download ${downloadId}; nothing was changed")
        }
        if (!filterMatches(download, item.polygon)) {
            throw new IllegalStateException("GBIF download ${downloadId} does not use the area of ${regionBefore ?: item.country}; nothing was changed")
        }

        File dir = new File(grailsApplication.config.uploadFilePath + File.separator + "tmp" + File.separator + downloadId)
        FileUtils.forceMkdir(dir)
        File archive = new File(dir, downloadId)
        retry { fetchArchive(download.downloadLink as String, archive) }

        int records = countRecords(archive)
        if (download.totalRecords != null && records != (download.totalRecords as int)) {
            throw new IllegalStateException("Archive has ${records} records but GBIF reports ${download.totalRecords}; nothing was changed")
        }

        gbifService.createOrUpdateGBIFResource(archive)

        DataResource after = DataResource.withTransaction { DataResource.findByUid(item.uid) }
        if (after.region != regionBefore) {
            throw new IllegalStateException("Area changed from ${regionBefore} to ${after.region} during the update: check this resource")
        }
        "Updated with ${records} records, area ${regionBefore ?: item.country}, GBIF download ${downloadId}"
    }

    /**
     * True when the download request sent to GBIF contains the expected polygon, so the filter did not change.
     */
    boolean filterMatches(Map download, String polygon) {
        String request = JsonOutput.toJson(download.request ?: [:]).replaceAll(/\s/, '')
        request.contains(polygon.replaceAll(/\s/, ''))
    }

    String waitForDownload(String downloadId) {
        for (int i = 0; i < MAX_POLLS; i++) {
            String status = retry { gbifService.getDownloadStatus(downloadId) }
            if (END_STATUSES.contains(status)) {
                return status
            }
            Thread.sleep(POLL_WAIT_MS)
        }
        return 'TIMEOUT'
    }

    void fetchArchive(String downloadLink, File target) {
        HttpGet get = new HttpGet(downloadLink)
        get.setHeader(HttpHeaders.AUTHORIZATION, basicAuth())
        def response = HttpClientBuilder.create().build().execute(get)
        try {
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new IOException("Archive download failed: HTTP ${response.getStatusLine().getStatusCode()}")
            }
            target.withOutputStream { os -> IOUtils.copy(response.getEntity().getContent(), os) }
        } finally {
            response.close()
        }
    }

    int countRecords(File archive) {
        ZipFile zip = new ZipFile(archive)
        try {
            int lines = 0
            new BufferedReader(new InputStreamReader(zip.getInputStream(zip.getEntry('occurrence.txt')), 'UTF-8')).withCloseable { reader ->
                while (reader.readLine() != null) {
                    lines++
                }
            }
            return lines - 1
        } finally {
            zip.close()
        }
    }

    String basicAuth() {
        String credentials = grailsApplication.config.gbifApiUser + ':' + grailsApplication.config.gbifApiPassword
        'Basic ' + Base64.getEncoder().encodeToString(credentials.getBytes('UTF-8'))
    }

    def retry(Closure work) {
        int attempt = 1
        while (true) {
            try {
                return work()
            } catch (Exception e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e
                }
                log.warn("[GBIF-SYNC] attempt ${attempt} failed (${e.message}), retrying in ${RETRY_WAIT_MS / 1000} seconds")
                Thread.sleep(RETRY_WAIT_MS)
                attempt++
            }
        }
    }
}
