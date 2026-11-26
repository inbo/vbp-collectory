package au.org.ala.collectory

import grails.testing.services.ServiceUnitTest
import spock.lang.Specification

class GbifSyncServiceSpec extends Specification implements ServiceUnitTest<GbifSyncService> {

    def gbifService = Mock(GbifService)

    def setup() {
        service.gbifService = gbifService
    }

    private DataResource resource(Map props) {
        def dr = new DataResource(uid: 'dr-test', name: 'Test', guid: 'some-guid',
                lastUpdated: new Date(0), connectionParameters: '{}')
        props.each { k, v -> dr[k] = v }
        dr
    }

    private Map download(Map predicate) {
        [request: [predicate: predicate]]
    }

    def "a resource with no area recorded is updated without an area filter"() {
        given:
        gbifService.getGbifDatasetLastUpdated('some-guid') >> new Date()

        when:
        def plan = service.planResource(resource([:]))

        then:
        plan.action == 'UPDATE'
        plan.scope == [type: 'none']
    }

    def "a resource with a country is updated with that country code"() {
        given:
        gbifService.getGbifDatasetLastUpdated('some-guid') >> new Date()

        when:
        def plan = service.planResource(resource(repatriationCountry: 'BE'))

        then:
        plan.action == 'UPDATE'
        plan.country == 'BE'
        plan.reason.contains('country BE')
    }

    def "a resource with a region is updated with that region code, even when a country is also recorded"() {
        given:
        gbifService.getGbifDatasetLastUpdated('some-guid') >> new Date()

        when:
        def plan = service.planResource(resource(repatriationCountry: 'BE', connectionParameters: '{"region":"BEL.2_1"}'))

        then:
        plan.action == 'UPDATE'
        plan.region == 'BEL.2_1'
        plan.scope == [type: 'gadm', value: 'BEL.2_1']
    }

    def "a resource without a GBIF key is refused"() {
        when:
        def plan = service.planResource(resource(guid: null))

        then:
        plan.action == 'REFUSE'
    }

    def "a resource already up to date with GBIF is skipped"() {
        given:
        gbifService.getGbifDatasetLastUpdated('some-guid') >> new Date(0)

        when:
        def plan = service.planResource(resource(lastUpdated: new Date(10000)))

        then:
        plan.action == 'SKIP'
    }

    def "a region download matches only the same GADM region"() {
        expect:
        service.filterMatches(download([type: 'and', predicates: [
                [type: 'equals', key: 'DATASET_KEY', value: 'x'],
                [type: 'equals', key: 'GADM_GID', value: 'BEL.2_1']]]), [type: 'gadm', value: 'BEL.2_1'])
        !service.filterMatches(download([type: 'and', predicates: [
                [type: 'equals', key: 'DATASET_KEY', value: 'x'],
                [type: 'equals', key: 'GADM_GID', value: 'BEL.1_1']]]), [type: 'gadm', value: 'BEL.2_1'])
    }

    def "a country download matches only the same country"() {
        expect:
        service.filterMatches(download([type: 'and', predicates: [
                [type: 'equals', key: 'DATASET_KEY', value: 'x'],
                [type: 'equals', key: 'COUNTRY', value: 'BE']]]), [type: 'country', value: 'BE'])
        !service.filterMatches(download([type: 'and', predicates: [
                [type: 'equals', key: 'DATASET_KEY', value: 'x'],
                [type: 'equals', key: 'COUNTRY', value: 'NL']]]), [type: 'country', value: 'BE'])
    }

    def "a download with a polygon matches only the stored polygon"() {
        expect:
        service.filterMatches(download([type: 'and', predicates: [
                [type: 'equals', key: 'DATASET_KEY', value: 'x'],
                [type: 'within', geometry: 'POLYGON((0 0,1 0,1 1,0 0))']]]), [type: 'polygon', value: 'POLYGON((0 0,1 0,1 1,0 0))'])
        !service.filterMatches(download([type: 'and', predicates: [
                [type: 'equals', key: 'DATASET_KEY', value: 'x'],
                [type: 'equals', key: 'COUNTRY', value: 'BE']]]), [type: 'polygon', value: 'POLYGON((0 0,1 0,1 1,0 0))'])
    }

    def "a download without an area filter matches only when GBIF applied no area predicate"() {
        expect:
        service.filterMatches(download([type: 'equals', key: 'DATASET_KEY', value: 'x']), [type: 'none'])
        !service.filterMatches(download([type: 'and', predicates: [
                [type: 'equals', key: 'DATASET_KEY', value: 'x'],
                [type: 'equals', key: 'COUNTRY', value: 'BE']]]), [type: 'none'])
    }

    def "a stored scope takes precedence over the region and country"() {
        given:
        gbifService.getGbifDatasetLastUpdated('some-guid') >> new Date()

        when:
        def plan = service.planResource(resource(repatriationCountry: 'BE',
                connectionParameters: '{"region":"BEL.2_1","scope":{"type":"country","value":"BE"}}'))

        then:
        plan.scope == [type: 'country', value: 'BE']
    }

    def "a resource without a stored scope uses its region, then its country, then no filter"() {
        expect:
        service.scopeOf(resource(connectionParameters: '{"region":"BEL.2_1"}')) == [type: 'gadm', value: 'BEL.2_1']
        service.scopeOf(resource(repatriationCountry: 'BE')) == [type: 'country', value: 'BE']
        service.scopeOf(resource([:])) == [type: 'none']
    }

    def "updates use the stored scope exactly"() {
        when:
        service.startScopedDownload('some-guid', [type: 'polygon', value: 'POLY'])
        service.startScopedDownload('some-guid', [type: 'gadm', value: 'BEL.2_1'])
        service.startScopedDownload('some-guid', [type: 'country', value: 'BE'])
        service.startScopedDownload('some-guid', [type: 'none'])

        then:
        1 * gbifService.startGBIFDownloadWithinGeometry('some-guid', 'POLY')
        1 * gbifService.startGBIFDownloadForRegion('some-guid', 'BEL.2_1')
        1 * gbifService.startGBIFDownload('some-guid', 'BE')
        1 * gbifService.startGBIFDownload('some-guid', null)
    }
}
