package com.local.eldermetro.domain

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.*

@Serializable data class GeoPoint(val latitude: Double, val longitude: Double) {
    val valid get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    fun api() = String.format(Locale.US, "%.6f,%.6f", longitude, latitude)
    fun distanceTo(other: GeoPoint): Double {
        val a = sin(Math.toRadians(other.latitude - latitude) / 2).pow(2) + cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) * sin(Math.toRadians(other.longitude - longitude) / 2).pow(2)
        return 6371000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
@Serializable data class Place(val id: String?, val name: String, val address: String, val city: String, val district: String, val point: GeoPoint, val demo: Boolean = false, val publicFacility: Boolean = false) {
    val identity get() = id?.takeIf { it.isNotBlank() } ?: (name.filterNot(Char::isWhitespace).lowercase(Locale.ROOT) + ":" + point.api())
}
@Serializable data class FavoriteDestination(val place: Place, val displayName: String, val sortOrder: Int)
@Serializable data class LocationFix(val point: GeoPoint, val city: String, val district: String, val measuredAt: Long)
@Serializable data class Station(val id: String, val name: String, val point: GeoPoint? = null)
@Serializable data class MetroSegment(
    val id: String, val line: String, val boarding: Station, val alighting: Station,
    val via: List<Station>, val stops: Int, val durationSeconds: Int,
    val direction: String? = null, val entrance: String? = null, val exit: String? = null,
    val walkBeforeMeters: Int = 0, val stationSeconds: List<Int> = emptyList(), val entrancePoint: GeoPoint? = null
) {
    val penultimate get() = via.lastOrNull() ?: boarding
    val valid get() = id.isNotBlank() && line.isNotBlank() && boarding.id.isNotBlank() && alighting.id.isNotBlank() && boarding.id != alighting.id && boarding.name.isNotBlank() && alighting.name.isNotBlank() && stops in 1..100 && via.size == stops - 1 && via.all { it.id.isNotBlank() && it.name.isNotBlank() } && durationSeconds in 1..21600 && walkBeforeMeters >= 0
}
@Serializable data class MetroRoute(val id: String, val origin: GeoPoint, val destination: Place, val durationSeconds: Int, val segments: List<MetroSegment>, val exitWalkMeters: Int, val generatedAt: Long, val demo: Boolean = false, val walkDurationSeconds: Int? = null) {
    val metroDurationSeconds get() = segments.sumOf { it.durationSeconds }
    val entryWalk get() = segments.firstOrNull()?.walkBeforeMeters ?: 0
    val transferWalk get() = segments.drop(1).sumOf { it.walkBeforeMeters }
    val totalWalk get() = entryWalk + transferWalk + exitWalkMeters
    val transfers get() = (segments.size - 1).coerceAtLeast(0)
    val totalStops get() = segments.sumOf { it.stops }
    val valid get() = origin.valid && destination.point.valid && destination.name.isNotBlank() && durationSeconds in 60..21600 && segments.isNotEmpty() && segments.all { it.valid } && segments.sumOf { it.durationSeconds } <= durationSeconds && exitWalkMeters >= 0 && totalWalk in 0..20000
}
@Serializable enum class TripPhase { IDLE, ROUTE_READY, WALKING_TO_STATION, WAITING_FOR_BOARD_CONFIRMATION, RIDING, APPROACHING_ALIGHT_STATION, TRANSFER_WALKING, WALKING_TO_DESTINATION, FINISHED }
@Serializable data class TripState(val route: MetroRoute? = null, val phase: TripPhase = TripPhase.IDLE, val segmentIndex: Int = 0, val startedElapsed: Long = 0, val bootCount: Int = -1, val dueElapsed: Long = 0, val reminderTriggered: Boolean = false, val reminderAcknowledged: Boolean = false, val reminderEnabled: Boolean = false, val reminderNotified: Boolean = false, val restoreMessage: String? = null) {
    val segment get() = route?.segments?.getOrNull(segmentIndex)
}
@Serializable data class LocalData(val favorites: List<FavoriteDestination> = emptyList(), val recent: List<Place> = emptyList(), val trip: TripState = TripState(), val speechEnabled: Boolean = false, val privacyAccepted: Boolean = false, val initialized: Boolean = false)

interface LocationRepository { suspend fun current(): LocationFix }
interface PlaceRepository { suspend fun search(query: String, location: LocationFix, poiId: String? = null): List<Place> }
interface RouteRepository { suspend fun routes(origin: LocationFix, destination: Place): List<MetroRoute> }
interface DestinationHistoryRepository { suspend fun recordSuccess(place: Place) }
interface TripRepository { suspend fun updateTrip(transform: (TripState) -> TripState): TripState }
