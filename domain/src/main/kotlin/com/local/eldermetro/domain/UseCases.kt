package com.local.eldermetro.domain

class SelectBestMetroRouteUseCase {
    operator fun invoke(candidates: List<MetroRoute>): MetroRoute? {
        val valid = candidates.filter { it.valid }
        val fastest = valid.minOfOrNull { it.durationSeconds } ?: return null
        val bounded = valid.filter { it.durationSeconds <= fastest * 1.5 && it.durationSeconds <= fastest + 1800 }
        val shortestWalk = bounded.minOf { it.totalWalk }
        val walkingBand = bounded.filter { it.totalWalk <= shortestWalk + 200 }
        val bandFastest = walkingBand.minOf { it.durationSeconds }
        return walkingBand.filter { it.durationSeconds <= bandFastest + 120 }
            .minWithOrNull(compareBy<MetroRoute> { it.transfers }.thenBy { it.durationSeconds }.thenBy { it.totalWalk }.thenBy { it.id })
    }
}
class SearchDestinationUseCase(private val places: PlaceRepository) {
    suspend operator fun invoke(query: String, location: LocationFix, poiId: String? = null): List<Place> =
        rankPlaces(places.search(query.trim(), location, poiId), query, location, poiId)
}
fun rankPlaces(places: List<Place>, query: String, location: LocationFix, poiId: String? = null): List<Place> = places
    .filter { it.city == location.city && it.point.valid && it.name.isNotBlank() }
    .distinctBy { it.identity }
    .sortedWith(compareByDescending<Place> { poiId != null && it.id == poiId }
        .thenByDescending { it.name == query.trim() }.thenByDescending { it.district == location.district && it.name.contains(query.trim()) }
        .thenBy { it.point.distanceTo(location.point) }.thenByDescending { it.name.contains(query.trim()) }
        .thenByDescending { it.publicFacility }.thenBy { it.identity }).take(3)

fun recentPlaces(existing: List<Place>, added: Place, favorites: List<FavoriteDestination>): List<Place> =
    (listOf(added) + existing).distinctBy { it.identity }.filter { p -> favorites.none { it.place.identity == p.identity } }.take(2)

/** Only trust a supplied, ordered line pattern that contains this ride's entire stop sequence. */
fun resolveDirection(segment: MetroSegment, orderedLine: List<Station>, terminal: String?): String? {
    if (terminal.isNullOrBlank() || orderedLine.lastOrNull()?.name != terminal) return null
    // Transit and line-detail APIs use different station ID namespaces.
    // Require exact names AND nearby coordinates when IDs differ, for every station.
    fun matches(a: Station, b: Station): Boolean = a.id == b.id ||
        (a.name == b.name && a.point != null && b.point != null && a.point.distanceTo(b.point) <= 50)
    val starts = orderedLine.indices.filter { matches(orderedLine[it],segment.boarding) }
    val ends = orderedLine.indices.filter { matches(orderedLine[it],segment.alighting) }
    if (starts.size != 1 || ends.size != 1) return null
    val start = starts.single()
    val end = ends.single()
    if (start < 0 || end <= start) return null
    val expected = listOf(segment.boarding) + segment.via + segment.alighting
    val actual = orderedLine.subList(start, end + 1)
    return terminal.takeIf { actual.size == expected.size && actual.zip(expected).all { (a,b) -> matches(a,b) } }
}
class CalculateReminderTimeUseCase {
    operator fun invoke(segment: MetroSegment): Long {
        require(segment.valid)
        if (segment.stops == 1) return 0
        val explicit = segment.stationSeconds
        val seconds = if (explicit.size == segment.stops && explicit.all { it in 1..900 } && kotlin.math.abs(explicit.sum() - segment.durationSeconds) <= 60) {
            explicit.dropLast(1).sum() - 30
        } else {
            segment.durationSeconds - (segment.durationSeconds / segment.stops).coerceIn(90, 240) - 30
        }
        return seconds.coerceAtLeast(0) * 1000L
    }
}
class StartTripUseCase {
    operator fun invoke(route: MetroRoute, nowWall: Long): TripState {
        require(route.valid)
        require(route.demo || nowWall - route.generatedAt in 0..300000) { "路线已过期，请重新规划" }
        return TripState(route = route, phase = TripPhase.WALKING_TO_STATION)
    }
}
object TripMachine {
    fun advance(state: TripState): TripState = when (state.phase) {
        TripPhase.WALKING_TO_STATION -> state.copy(phase = TripPhase.WAITING_FOR_BOARD_CONFIRMATION)
        TripPhase.TRANSFER_WALKING -> state.copy(phase = TripPhase.WAITING_FOR_BOARD_CONFIRMATION, segmentIndex = state.segmentIndex + 1, reminderTriggered = false, reminderAcknowledged = false, reminderEnabled = false)
        TripPhase.APPROACHING_ALIGHT_STATION -> state.copy(phase = if (state.segmentIndex < (state.route?.segments?.lastIndex ?: 0)) TripPhase.TRANSFER_WALKING else TripPhase.WALKING_TO_DESTINATION, reminderAcknowledged = true, reminderEnabled = false)
        TripPhase.RIDING -> state.copy(phase = if (state.segmentIndex < (state.route?.segments?.lastIndex ?: 0)) TripPhase.TRANSFER_WALKING else TripPhase.WALKING_TO_DESTINATION, reminderAcknowledged = true, reminderEnabled = false)
        TripPhase.WALKING_TO_DESTINATION -> state.copy(phase = TripPhase.FINISHED, reminderEnabled = false)
        else -> state
    }
    fun board(state: TripState, elapsed: Long, boot: Int, enabled: Boolean): TripState {
        if (state.phase != TripPhase.WAITING_FOR_BOARD_CONFIRMATION) return state
        val segment = state.segment ?: return state
        return state.copy(phase = TripPhase.RIDING, startedElapsed = elapsed, bootCount = boot, dueElapsed = elapsed + CalculateReminderTimeUseCase()(segment), reminderTriggered = false, reminderAcknowledged = false, reminderNotified = false, reminderEnabled = enabled, restoreMessage = null)
    }
    fun trigger(state: TripState, segmentId: String, now: Long, nearby: Boolean = false): TripState =
        if (state.phase == TripPhase.RIDING && state.reminderEnabled && !state.reminderTriggered && state.segment?.id == segmentId && (nearby || now >= state.dueElapsed)) state.copy(phase = TripPhase.APPROACHING_ALIGHT_STATION, reminderTriggered = true) else state

    fun restore(state: TripState, elapsed: Long, boot: Int): TripState {
        if (state.phase !in listOf(TripPhase.RIDING, TripPhase.APPROACHING_ALIGHT_STATION)) return state
        if (state.segment == null || state.bootCount != boot || elapsed < state.startedElapsed) return state.copy(phase = TripPhase.WAITING_FOR_BOARD_CONFIRMATION, reminderEnabled = false, reminderTriggered = false, restoreMessage = "手机已重启或计时失效，请核对所在站点；仍在原上车站时才重新确认上车。")
        return trigger(state, state.segment!!.id, elapsed)
    }
}
fun walkingWarning(meters: Int): String? = when { meters > 1500 -> "步行很多，建议考虑打车"; meters > 1000 -> "步行较多"; meters > 500 -> "步行有一点多"; else -> null }
