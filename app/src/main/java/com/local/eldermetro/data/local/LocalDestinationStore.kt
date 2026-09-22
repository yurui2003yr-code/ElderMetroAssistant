package com.local.eldermetro.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.eldermetro.domain.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

private val Context.localStore by preferencesDataStore("elder_metro")
class LocalDestinationStore(context: Context) : DestinationHistoryRepository, TripRepository {
    private val store = context.applicationContext.localStore
    private val key = stringPreferencesKey("data_v1")
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(value: String?): LocalData = value?.let { runCatching { json.decodeFromString<LocalData>(it) }.getOrNull() } ?: LocalData()
    val data: Flow<LocalData> = store.data.map { decode(it[key]) }
    suspend fun change(transform: (LocalData) -> LocalData): LocalData {
        var result = LocalData()
        store.edit { prefs -> result = transform(decode(prefs[key])); prefs[key] = json.encodeToString(result) }
        return result
    }
    override suspend fun updateTrip(transform: (TripState) -> TripState): TripState = change { it.copy(trip = transform(it.trip)) }.trip
    override suspend fun recordSuccess(place: Place) { change { it.copy(recent = recentPlaces(it.recent, place, it.favorites)) } }
    suspend fun saveFavorite(place: Place, label: String) { change { data ->
        val old = data.favorites.filterNot { it.place.identity == place.identity }
        val sameMode = old.count { it.place.demo == place.demo }
        require(sameMode < 5) { "最多设置5个常用地点，请先移除一个" }
        data.copy(favorites = old + FavoriteDestination(place, label.trim().ifEmpty { place.name }, sameMode), recent = data.recent.filterNot { it.identity == place.identity })
    } }
}
