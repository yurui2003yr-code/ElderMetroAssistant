package com.local.eldermetro

import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.local.eldermetro.app.ElderMetroApplication
import com.local.eldermetro.domain.*
import com.local.eldermetro.notification.TripNotificationManager
import com.local.eldermetro.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

class ReminderIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val store get() = (compose.activity.application as ElderMetroApplication).container.store
    @Before fun setup() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant com.local.eldermetro android.permission.POST_NOTIFICATIONS").close()
        TripNotificationManager(compose.activity).clear()
        runBlocking { store.updateTrip { TripState() } }
    }
    @After fun cleanup() {
        runBlocking { store.updateTrip { TripState() } }
        compose.activity.stopService(Intent(compose.activity,TripTrackingService::class.java))
        TripStateRestorer.cancel(compose.activity)
    }
    private fun riding(): TripState {
        val route = DemoData.routes(DemoData.places.first(),System.currentTimeMillis()).first()
        return TripMachine.board(TripState(route=route,phase=TripPhase.WAITING_FOR_BOARD_CONFIRMATION),SystemClock.elapsedRealtime(),TripStateRestorer.boot(compose.activity),true)
    }
    @Test fun parallelBackupAndServiceClaimOnlyOneReminder() = runBlocking {
        store.updateTrip { riding().copy(dueElapsed=0) }
        coroutineScope { List(8) { async(Dispatchers.Default) { TripStateRestorer.check(compose.activity) } }.awaitAll() }
        val state = store.data.first().trip
        assertTrue(state.reminderTriggered); assertTrue(state.reminderNotified)
        val manager = compose.activity.getSystemService(NotificationManager::class.java)
        assertEquals(1,manager.activeNotifications.count { it.id == TripNotificationManager.ALERT })
        val firstTime = manager.activeNotifications.first { it.id == TripNotificationManager.ALERT }.postTime
        TripStateRestorer.check(compose.activity)
        assertEquals(firstTime,manager.activeNotifications.first { it.id == TripNotificationManager.ALERT }.postTime)
    }
    @Test fun backgroundServiceTriggersWithoutLocationPermission() {
        runBlocking { store.updateTrip { riding().copy(dueElapsed=SystemClock.elapsedRealtime()+2000) } }
        compose.runOnUiThread { ContextCompat.startForegroundService(compose.activity,Intent(compose.activity,TripTrackingService::class.java)) }
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        runBlocking { withTimeout(15000) { while (!store.data.first().trip.reminderNotified) delay(100) } }
        val manager = compose.activity.getSystemService(NotificationManager::class.java)
        assertTrue(manager.activeNotifications.any { it.id == TripNotificationManager.ALERT })
    }
}
