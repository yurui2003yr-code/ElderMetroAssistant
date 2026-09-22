package com.local.eldermetro.ui

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import android.content.Intent
import androidx.core.content.ContextCompat
import com.local.eldermetro.service.TripTrackingService
import com.local.eldermetro.service.TripStateRestorer
import com.local.eldermetro.notification.TripNotificationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.eldermetro.app.ElderMetroApplication
import com.local.eldermetro.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class HomeViewState(val page: String = "home", val busy: Boolean = false, val error: String? = null, val candidates: List<Place> = emptyList(), val route: MetroRoute? = null, val addingFavorite: Boolean = false, val walking: WalkingRoute? = null, val walkTarget: Place? = null, val walkReturn: String = "destination", val walkReason: String? = null, val selectedPlace: Place? = null, val searchOrigin: String = "input")
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val container = (app as ElderMetroApplication).container
    val local = container.store.data.stateIn(viewModelScope, SharingStarted.Eagerly, LocalData())
    private val mutable = MutableStateFlow(HomeViewState())
    val ui = mutable.asStateFlow()
    init { launch {
        container.store.change { if (it.initialized) it else it.copy(favorites = if (container.demo) DemoData.favorites else emptyList(), initialized = true) }
        container.store.updateTrip { TripMachine.restore(it, SystemClock.elapsedRealtime(), boot()) }
        if (container.store.data.first().trip.phase !in listOf(TripPhase.IDLE, TripPhase.FINISHED)) mutable.update { it.copy(page = "trip") }
    } }
    private fun launch(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.update { it.copy(busy = false, error = "操作未完成，请重试") } } } }
    fun message(text: String) { mutable.update { it.copy(error = text) } }
    fun page(value: String) { mutable.update { it.copy(page = value, error = null) } }
    fun back() { page(when (ui.value.page) {
        "walk" -> ui.value.walkReturn
        "confirm" -> if (ui.value.candidates.size > 1) "destination" else "home"
        "route", "noMetro" -> "confirm"
        "destination" -> ui.value.searchOrigin
        else -> "home"
    }) }
    fun retryLive() { if (container.realSearchAvailable) { container.useLive(); mutable.value = HomeViewState(error = "已恢复真实搜索，请确认手机定位和网络已开启") } }
    fun search(query: String, favorite: Boolean = false) {
        if (query.isBlank() || ui.value.busy) return
        if (!container.demo && !local.value.privacyAccepted) { message("请先同意真实地点查询并开启定位"); return }
        launch {
            mutable.update { it.copy(busy = true, error = null, route = null, addingFavorite = favorite, searchOrigin = if (favorite) "settings" else if (it.page == "voice") "voice" else "input") }
            try {
                val list = SearchDestinationUseCase(container.places)(query, container.location())
                mutable.update { it.copy(busy = false, candidates = list, page = "destination", error = if (list.isEmpty()) {
                    if (container.demo) "当前是演示模式，不能搜索真实地点。请试试“市人民医院”“人民公园”或“社区中心”。真实搜索需由家属配置高德服务。"
                    else "没有找到这个地方，请重新说一次或输入文字"
                } else null) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { fallback() }
        }
    }
    fun select(place: Place) { mutable.update { it.copy(page = "confirm", candidates = listOf(place), selectedPlace = place, addingFavorite = false, error = null, route = null) } }
    fun choose(place: Place) { mutable.update { it.copy(page = "confirm", selectedPlace = place, error = null) } }
    fun confirm(place: Place) {
        if (ui.value.busy) return
        if (!container.demo && !local.value.privacyAccepted) { message("请先在设置中同意使用高德查询并开启定位"); return }
        launch {
            if (ui.value.addingFavorite) { container.store.saveFavorite(place, place.name); page("settings"); return@launch }
            mutable.update { it.copy(busy = true, error = null, route = null) }
            try {
                require(place.demo == container.demo)
                val location = container.location()
                require(location.city == place.city)
                val route = SelectBestMetroRouteUseCase()(container.routes.routes(location, place))
                if (route == null) { mutable.update { it.copy(busy = false, page = "noMetro", selectedPlace = place, route = null) }; return@launch }
                container.store.recordSuccess(place)
                mutable.update { it.copy(busy = false, route = route, page = "route") }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { fallback() }
        }
    }
    private fun fallback() {
        val reason = if (container.demo) "演示操作失败，请重试" else "网络或定位异常，请检查网络、定位开关和位置权限后重试。目的地已保留。"
        mutable.update { it.copy(busy = false, route = null, error = reason) }
    }
    fun start() { val route = ui.value.route ?: return; launch {
        if (local.value.trip.phase !in listOf(TripPhase.IDLE,TripPhase.FINISHED)) { message("已有进行中的行程，请先回首页继续或结束该行程"); return@launch }
        if (!route.demo) {
            val manager = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
            val online = manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            if (!online) { fallback(); return@launch }
            try {
                mutable.update { it.copy(busy = true) }
                val fix = container.location()
                if (fix.point.distanceTo(route.origin) > 150) { mutable.update { it.copy(busy = false, route = null, page = "home", error = "当前位置已改变，请重新选择目的地规划") }; return@launch }
                mutable.update { it.copy(busy = false) }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { fallback(); return@launch }
        }
        try { container.store.updateTrip { StartTripUseCase()(route, System.currentTimeMillis()) }; page("trip") }
        catch (_: IllegalArgumentException) { mutable.update { it.copy(route = null, page = "home", error = "路线已过期，请重新规划") } }
    } }
    private fun stopTracking() { getApplication<Application>().stopService(Intent(getApplication(),TripTrackingService::class.java)); TripStateRestorer.cancel(getApplication()) }
    fun advance() { launch { container.store.updateTrip { TripMachine.advance(it) }; stopTracking() } }
    fun disableReminder() { launch { container.store.updateTrip { it.copy(reminderEnabled = false) }; stopTracking() } }
    fun undoBoard() { launch {
        container.store.updateTrip { state -> if (state.phase in listOf(TripPhase.RIDING, TripPhase.APPROACHING_ALIGHT_STATION))
            state.copy(phase = TripPhase.WAITING_FOR_BOARD_CONFIRMATION, reminderEnabled = false, reminderTriggered = false, reminderAcknowledged = false, reminderNotified = false, startedElapsed = 0, dueElapsed = 0) else state }
        stopTracking()
    } }
    fun board(enabled: Boolean) { launch {
        val actualEnabled = enabled && TripNotificationManager(getApplication()).enabled()
        val state = container.store.updateTrip { TripMachine.board(it, SystemClock.elapsedRealtime(), boot(), actualEnabled) }
        if (actualEnabled && state.phase == TripPhase.RIDING) startTracking(state)
        else if (enabled && !actualEnabled) message("通知或提醒渠道已关闭，到站提醒未启用，请自行留意报站")
    } }
    private suspend fun startTracking(state: TripState) {
        try { ContextCompat.startForegroundService(getApplication(), Intent(getApplication(),TripTrackingService::class.java)); TripStateRestorer.schedule(getApplication(),state) }
        catch (_: Exception) { container.store.updateTrip { it.copy(reminderEnabled = false, restoreMessage = "后台提醒启动失败，请留意报站") } }
    }
    fun resumeTracking() { launch {
        val state = container.store.updateTrip { TripMachine.restore(it,SystemClock.elapsedRealtime(),boot()) }
        if (state.reminderEnabled && state.phase in listOf(TripPhase.RIDING,TripPhase.APPROACHING_ALIGHT_STATION)) startTracking(state)
    } }
    fun simulate() { launch { container.store.updateTrip { s -> if (s.route?.demo == true) TripMachine.trigger(s, s.segment?.id.orEmpty(), Long.MAX_VALUE) else s }; TripStateRestorer.check(getApplication()) } }
    fun finish() { launch { container.store.updateTrip { TripState() }; stopTracking(); page("home") } }
    fun removeFavorite(id: String) { launch { container.store.change { it.copy(favorites = it.favorites.filterNot { f -> f.place.identity == id }) } } }
    fun renameFavorite(id: String, label: String) { if (label.isBlank()) return; launch { container.store.change { it.copy(favorites = it.favorites.map { f -> if (f.place.identity == id) f.copy(displayName = label.trim()) else f }) } } }
    fun speech(enabled: Boolean) { launch { container.store.change { it.copy(speechEnabled = enabled) } } }
    fun acceptPrivacy() { launch { container.store.change { it.copy(privacyAccepted = true) } } }
    private suspend fun loadWalking(place: Place, origin: GeoPoint?, back: String, reason: String?) {
        mutable.update { it.copy(page="walk",busy=true,error=null,walking=null,walkTarget=place,walkReturn=back,walkReason=reason) }
        try {
            val walk = if (place.demo) WalkingRoute(DemoData.origin.point,place,600,600,listOf("沿人行道直走","在路口按行人信号灯过街","到达目的地入口"),emptyList())
                else container.walking(origin ?: container.liveLocation.current().point,place)
            mutable.update { it.copy(busy=false,walking=walk,error=if(walk == null) "暂未找到可用步行路线，请打开高德查看其他出行方式" else null) }
            if (walk != null && back == "destination") container.store.recordSuccess(place)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(busy=false,walking=null,error="步行查询失败，请检查网络和定位后重试，或打开高德") } }
    }
    fun walkTo(place: Place, back: String = ui.value.page) {
        if (ui.value.busy) return
        if (!place.demo && !local.value.privacyAccepted) { message("请先在首页同意查询并开启定位"); return }
        launch { loadWalking(place,null,back,null) }
    }
    fun walkToStation(route: MetroRoute, back: String) {
        val s = route.segments.first()
        val point = s.entrancePoint ?: s.boarding.point
        if (point == null && !route.demo) { message("进站位置暂缺，请在高德中核对车站入口"); return }
        walkTo(Place(null,s.boarding.name + s.entrance.orEmpty(),"",route.destination.city,route.destination.district,point ?: route.origin,route.demo),back)
    }
    fun refreshWalking() { ui.value.walkTarget?.let { walkTo(it,ui.value.walkReturn) } }
    fun navigateWalking(place: Place) {
        if (place.demo) { message("这是演示路线，不会导航到虚构地点"); return }
        message(com.local.eldermetro.taxi.openWalkingNavigation(getApplication(),place))
    }
    fun taxiPlace(place: Place) {
        if (place.demo) { message("这是演示路线，不会发送虚构起终点；请在高德中自行选择真实地点"); return }
        message(com.local.eldermetro.taxi.openAMapRoute(getApplication(),place))
    }
    fun taxi(route: MetroRoute) { launch {
        if (route.demo) { message("这是演示路线，不会发送虚构起终点；请在高德中自行选择真实地点"); return@launch }
        try { message(com.local.eldermetro.taxi.openAMapRoute(getApplication(),route.destination)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { message("无法获取当前位置，请手动打开高德选择打车") }
    } }
    fun boot() = Settings.Global.getInt(getApplication<Application>().contentResolver, Settings.Global.BOOT_COUNT, -1)
}
