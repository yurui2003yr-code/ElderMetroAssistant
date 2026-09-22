package com.local.eldermetro.domain
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
class CoreTest {
    private val route = DemoData.routes(DemoData.places.first(), 1000).first()
    @Test fun demoRouteIsValid() { assertTrue(route.valid); assertEquals(800, route.totalWalk); assertEquals(250, route.entryWalk); assertEquals(120, route.transferWalk); assertEquals(430, route.exitWalkMeters) }
    @Test fun rejectsNoMetro() { assertNull(SelectBestMetroRouteUseCase()(listOf(route.copy(segments = emptyList())))) }
    @Test fun selectsOnlyOneBoundedWalkingRoute() { assertEquals("recommended", SelectBestMetroRouteUseCase()(DemoData.routes(DemoData.places.first(), 0))?.id) }
    @Test fun rejectsMalformedTime() { assertNull(SelectBestMetroRouteUseCase()(listOf(route.copy(durationSeconds = -1), route.copy(durationSeconds = 200000)))) }
    @Test fun rejectsMissingStation() { assertFalse(route.copy(segments = listOf(route.segments[0].copy(stops = 8))).valid) }
    @Test fun toleranceIsOrderIndependent() { val a = route.copy(id = "a", exitWalkMeters = 10); val b = route.copy(id = "b", exitWalkMeters = 210, durationSeconds = 2600); val c = route.copy(id = "c", exitWalkMeters = 410, durationSeconds = 2400); val select = SelectBestMetroRouteUseCase(); assertEquals("b", select(listOf(a,b,c))?.id); assertEquals(select(listOf(a,b,c)), select(listOf(c,b,a))) }
    @Test fun thirtyMinuteCapApplies() { val a = route.copy(durationSeconds = 6000); val b = route.copy(durationSeconds = 8000, exitWalkMeters = 0); assertEquals(a, SelectBestMetroRouteUseCase()(listOf(a,b))) }
    @Test fun fewerTransfersWhenTimeClose() { val b = route.copy(id = "b", segments = listOf(route.segments[0]), exitWalkMeters = 550, durationSeconds = 2950); assertEquals(b, SelectBestMetroRouteUseCase()(listOf(route,b))) }
    @Test fun directionRequiresOrderedEvidence() { val s = route.segments.first(); val stations = listOf(s.boarding) + s.via + s.alighting; assertEquals(s.alighting.name, resolveDirection(s, stations, s.alighting.name)); assertNull(resolveDirection(s, stations.reversed(), s.boarding.name)); assertNull(resolveDirection(s, emptyList(), "猜测终点")) }
    @Test fun directionSupportsDifferentIdsOnlyWithFullSpatialEvidence() {
        val original = route.segments.first()
        val stations = (listOf(original.boarding) + original.via + original.alighting).mapIndexed { i,s -> s.copy(point=GeoPoint(31.0 + i * 0.01,121.0)) }
        val s = original.copy(boarding=stations.first(),alighting=stations.last(),via=stations.drop(1).dropLast(1))
        val otherIds = stations.map { it.copy(id="detail-${it.id}") }
        assertEquals(s.alighting.name,resolveDirection(s,otherIds,s.alighting.name))
        assertNull(resolveDirection(s,otherIds.map { it.copy(point=null) },s.alighting.name))
        assertNull(resolveDirection(s,otherIds.map { it.copy(point=GeoPoint(30.0,120.0)) },s.alighting.name))
        assertNull(resolveDirection(s,otherIds.toMutableList().also { it[1]=it[1].copy(name="不同站") },s.alighting.name))
        assertNull(resolveDirection(s,listOf(otherIds.first()) + otherIds,s.alighting.name))
    }
    @Test fun onlyTwoDistinctRecentPlaces() { val p = DemoData.places; assertEquals(listOf(p[3],p[4]), recentPlaces(listOf(p[4],p[3],p[5]), p[3], emptyList())) }
    @Test fun favoritesAreNotRepeated() { assertEquals(emptyList(), recentPlaces(emptyList(), DemoData.places[0], DemoData.favorites)) }
    @Test fun noPoiIdentityNormalized() { val p = DemoData.places[0].copy(id = null, name = "公 园"); assertEquals(p.identity, p.copy(name = "公园").identity) }
    @Test fun reminderTiming() { assertEquals(690000, CalculateReminderTimeUseCase()(route.segments[0])); val one = route.segments[0].copy(stops = 1, via = emptyList()); assertEquals(0, CalculateReminderTimeUseCase()(one)) }
    @Test fun explicitStationTimingPreferred() { assertEquals(570000, CalculateReminderTimeUseCase()(route.segments[0].copy(stationSeconds = listOf(100,100,200,200,300)))) }
    @Test fun abnormalAverageClamped() { assertEquals(180000, CalculateReminderTimeUseCase()(route.segments[0].copy(durationSeconds = 300))); assertEquals(1730000, CalculateReminderTimeUseCase()(route.segments[0].copy(durationSeconds = 2000))) }
    private fun riding() = TripMachine.board(TripState(route = route, phase = TripPhase.WAITING_FOR_BOARD_CONFIRMATION), 100, 2, true)
    @Test fun triggerOnceAndIgnoreWrongSegment() { val s = riding(); assertEquals(s, TripMachine.trigger(s,"wrong",Long.MAX_VALUE)); val triggered = TripMachine.trigger(s,s.segment!!.id,Long.MAX_VALUE); assertTrue(triggered.reminderTriggered); assertEquals(triggered, TripMachine.trigger(triggered,triggered.segment!!.id,Long.MAX_VALUE)) }
    @Test fun disabledReminderNeverClaimsEnabled() { val s = riding().copy(reminderEnabled = false); assertEquals(s, TripMachine.trigger(s,s.segment!!.id,Long.MAX_VALUE)) }
    @Test fun acknowledgementAndTransfer() { val s = riding(); val acknowledged = TripMachine.advance(TripMachine.trigger(s,s.segment!!.id,Long.MAX_VALUE)); assertEquals(TripPhase.TRANSFER_WALKING, acknowledged.phase); val next = TripMachine.advance(acknowledged); assertEquals(1,next.segmentIndex); assertEquals(TripPhase.WAITING_FOR_BOARD_CONFIRMATION,next.phase); assertFalse(next.reminderTriggered) }
    @Test fun processRestorePreservesAndTriggersOverdue() { val s = Json.decodeFromString<TripState>(Json.encodeToString(riding())); assertEquals(riding(),s); assertEquals(TripPhase.APPROACHING_ALIGHT_STATION, TripMachine.restore(s,s.dueElapsed+1,2).phase) }
    @Test fun rebootInvalidatesTiming() { val restored = TripMachine.restore(riding(),0,3); assertEquals(TripPhase.WAITING_FOR_BOARD_CONFIRMATION, restored.phase); assertFalse(restored.reminderEnabled) }
    @Test fun oldRealRouteCannotStart() { assertFailsWith<IllegalArgumentException> { StartTripUseCase()(route.copy(demo=false),400000) } }
    @Test fun crossCitySearchExcluded() { assertTrue(rankPlaces(DemoData.places,"人民公园",DemoData.origin.copy(city="其他城市")).isEmpty()) }
    @Test fun warningsAtBoundaries() { assertNull(walkingWarning(500)); assertEquals("步行有一点多",walkingWarning(501)); assertEquals("步行较多",walkingWarning(1001)); assertEquals("步行很多，建议考虑打车",walkingWarning(1501)) }
}
