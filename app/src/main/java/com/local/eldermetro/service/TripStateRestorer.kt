package com.local.eldermetro.service

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.work.*
import com.local.eldermetro.app.ElderMetroApplication
import com.local.eldermetro.domain.*
import com.local.eldermetro.notification.TripNotificationManager
import java.util.concurrent.TimeUnit

object TripStateRestorer {
    fun boot(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT,-1)
    fun schedule(context: Context, state: TripState) {
        val work = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay((state.dueElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0),TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("segment" to state.segment?.id,"start" to state.startedElapsed,"boot" to state.bootCount)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("metro-reminder",ExistingWorkPolicy.REPLACE,work)
    }
    fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("metro-reminder"); TripNotificationManager(context).clear() }
    suspend fun check(context: Context, nearby: Boolean = false): TripState {
        val store = (context.applicationContext as ElderMetroApplication).container.store
        val notices = TripNotificationManager(context)
        var claimed: TripState? = null
        val result = store.updateTrip { old ->
            var state = TripMachine.restore(old,SystemClock.elapsedRealtime(),boot(context))
            if (!notices.enabled()) state = state.copy(reminderEnabled = false)
            if (nearby) state = TripMachine.trigger(state,state.segment?.id.orEmpty(),SystemClock.elapsedRealtime(),true)
            if (state.phase == TripPhase.APPROACHING_ALIGHT_STATION && state.reminderEnabled && !state.reminderAcknowledged && !state.reminderNotified) {
                claimed = state; state = state.copy(reminderNotified = true)
            }
            state
        }
        claimed?.let { notices.alert(it) }
        return result
    }
}
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val store = (applicationContext as ElderMetroApplication).container.store
        var matches = false
        store.updateTrip { s -> matches = s.segment?.id == inputData.getString("segment") && s.startedElapsed == inputData.getLong("start",-1) && s.bootCount == inputData.getInt("boot",-2); s }
        if (matches) TripStateRestorer.check(applicationContext)
        return Result.success()
    }
}
