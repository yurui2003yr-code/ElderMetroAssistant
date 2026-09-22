package com.local.eldermetro.service
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.local.eldermetro.app.ElderMetroApplication
import com.local.eldermetro.domain.*
import com.local.eldermetro.notification.TripNotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.local.eldermetro.speech.ConsiderateSpeechController

class TripTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container get() = (application as ElderMetroApplication).container
    private val notices by lazy { TripNotificationManager(this) }
    private var running: Job? = null
    private var locationJob: Job? = null
    private var speech: ConsiderateSpeechController? = null
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val locationAllowed = ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val type = if (locationAllowed && Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            if (Build.VERSION.SDK_INT >= 29) startForeground(TripNotificationManager.RUNNING,notices.ongoing(TripState()),type) else startForeground(TripNotificationManager.RUNNING,notices.ongoing(TripState()))
        } catch (_: Exception) { scope.launch { container.store.updateTrip { it.copy(reminderEnabled = false, restoreMessage = "后台提醒启动失败，请留意车厢报站") }; stopSelf() }; return START_NOT_STICKY }
        if (running?.isActive == true) return START_STICKY
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"ElderMetro:Trip").apply { acquire(6 * 60 * 60 * 1000L) }
        running = scope.launch {
            var counter = 0
            var spoken = false
            while (isActive) {
                val before = container.store.data.first().trip
                val state = TripStateRestorer.check(this@TripTrackingService)
                if (state.phase !in listOf(TripPhase.RIDING,TripPhase.APPROACHING_ALIGHT_STATION) || !state.reminderEnabled) { TripStateRestorer.cancel(this@TripTrackingService); stopSelf(); break }
                val speechEnabled = container.store.data.first().speechEnabled
                if (speechEnabled && speech == null) speech = ConsiderateSpeechController(application)
                if (!speechEnabled) { speech?.close(); speech = null }
                if (state.phase == TripPhase.APPROACHING_ALIGHT_STATION) wake?.let { if (it.isHeld) it.release() }
                if (state.phase == TripPhase.APPROACHING_ALIGHT_STATION && !before.reminderNotified && !spoken && speechEnabled) {
                    spoken = true; speech?.sayIfIdle("准备下车。预计下一站到达，请核对车厢报站",(application as ElderMetroApplication).activityResumed)
                }
                if (state.phase == TripPhase.APPROACHING_ALIGHT_STATION) {
                    androidx.work.WorkManager.getInstance(this@TripTrackingService).cancelUniqueWork("metro-reminder")
                    // Keep the delivered alert, but do not poll indefinitely while waiting for acknowledgement.
                    if (speechEnabled) delay(10000)
                    stopSelf(); break
                }
                if (counter++ % 60 == 0 && state.route?.demo == false && state.phase == TripPhase.RIDING && locationJob?.isActive != true) {
                    val point = state.segment?.penultimate?.point
                    if (point != null) locationJob = scope.launch {
                        try { val actual = container.liveLocation.point(); if (actual.distanceTo(point) < 350) {
                            // Re-check identity after asynchronous location lookup; never advance another ride.
                            val latest = container.store.data.first().trip
                            if (latest.segment?.id == state.segment?.id && latest.startedElapsed == state.startedElapsed) TripStateRestorer.check(this@TripTrackingService,true)
                        } } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Time reminder remains active. */ }
                    }
                }
                delay(1000)
            }
        }
        return START_STICKY
    }
    override fun onDestroy() {
        scope.cancel(); speech?.close(); wake?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
