package com.local.eldermetro

import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.local.eldermetro.app.ElderMetroApplication
import com.local.eldermetro.domain.*
import com.local.eldermetro.notification.TripNotificationManager
import com.local.eldermetro.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Coexistence tests never place a call: ACTION_DIAL only opens the keypad. */
class CoexistenceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ElderMetroApplication
    private val store get() = app.container.store
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    @Before fun reset() {
        shell("pm grant com.local.eldermetro android.permission.POST_NOTIFICATIONS")
        runBlocking { store.change { LocalData(favorites=DemoData.favorites,initialized=true) } }
        TripNotificationManager(app).clear()
        compose.waitForIdle()
    }
    @After fun cleanup() {
        runBlocking { store.updateTrip { TripState() } }
        app.stopService(Intent(app,TripTrackingService::class.java))
        TripStateRestorer.cancel(app)
    }
    private fun startTimer(delayMs: Long = 1500) {
        val route = DemoData.routes(DemoData.places.first(),System.currentTimeMillis()).first()
        runBlocking { store.updateTrip { TripMachine.board(TripState(route=route,phase=TripPhase.WAITING_FOR_BOARD_CONFIRMATION),SystemClock.elapsedRealtime(),TripStateRestorer.boot(app),true).copy(dueElapsed=SystemClock.elapsedRealtime()+delayMs) } }
        compose.runOnUiThread { ContextCompat.startForegroundService(app,Intent(app,TripTrackingService::class.java)) }
    }
    private fun waitUntil(condition: () -> Boolean) { runBlocking { withTimeout(15000) { while (!condition()) delay(100) } } }
    private fun foregroundContains(packageName: String): Boolean = shell("dumpsys activity activities").lineSequence().filter { it.contains("mResumedActivity") || it.contains("topResumedActivity") }.any { it.contains(packageName + "/") }
    private fun externalAppStaysUsable(intent: Intent) {
        val info = app.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)
        assertNotNull("Test image must provide this external activity",info)
        val target = info!!.activityInfo.packageName
        assertNotEquals(app.packageName,target)
        startTimer(2500)
        compose.runOnUiThread { compose.activity.startActivity(intent) }
        waitUntil { foregroundContains(target) }
        waitUntil { runBlocking { store.data.first().trip.reminderNotified } }
        // Notification creation must never bring the metro Activity over the external app.
        assertTrue(foregroundContains(target)); assertFalse(app.activityResumed)
        assertNotNull(app.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == TripNotificationManager.ALERT })
        shell("input keyevent KEYCODE_HOME")
        waitUntil { !foregroundContains(app.packageName) && !foregroundContains(target) }
    }
    @Test fun settingsRemainForegroundWhenReminderArrives() { externalAppStaysUsable(Intent(Settings.ACTION_SETTINGS)) }
    @Test fun dialerRemainsForegroundWhenReminderArrivesWithoutPlacingCall() {
        Assume.assumeTrue("Test image has no dialer; this check must be run on the target phone",shell("cmd package resolve-activity --brief -a android.intent.action.DIAL -d tel:12345").contains('/'))
        externalAppStaysUsable(Intent(Intent.ACTION_DIAL,Uri.parse("tel:12345")))
    }
    @Test fun reminderCanBeDismissedWithBackWithoutAdvancingTrip() {
        val route = DemoData.routes(DemoData.places.first(),System.currentTimeMillis()).first()
        runBlocking { store.updateTrip { TripState(route=route,phase=TripPhase.APPROACHING_ALIGHT_STATION,reminderTriggered=true) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("准备下车").fetchSemanticsNodes().isNotEmpty() }
        shell("input keyevent KEYCODE_BACK")
        compose.waitUntil(5000) { compose.onAllNodesWithText("准备下车").fetchSemanticsNodes().isEmpty() }
        assertEquals(TripPhase.APPROACHING_ALIGHT_STATION,runBlocking { store.data.first().trip.phase })
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("家属设置").assertExists()
        shell("input keyevent KEYCODE_HOME")
        waitUntil { !app.activityResumed }
    }
    @Test fun notificationStopActionEndsTripAndClearsNotifications() {
        startTimer(60000)
        val manager = app.getSystemService(NotificationManager::class.java)
        waitUntil { manager.activeNotifications.any { it.id == TripNotificationManager.RUNNING } }
        val notification = manager.activeNotifications.first { it.id == TripNotificationManager.RUNNING }.notification
        val action = notification.actions.first { it.title.toString() == "结束行程" }
        action.actionIntent.send()
        waitUntil { runBlocking { store.data.first().trip.phase == TripPhase.IDLE } && manager.activeNotifications.none { it.id in listOf(TripNotificationManager.RUNNING,TripNotificationManager.ALERT) } }
        val power = shell("dumpsys power")
        assertTrue(power.contains("Wake Locks:") && power.contains("Suspend Blockers:"))
        assertFalse(power.substringAfter("Wake Locks:").substringBefore("Suspend Blockers:").contains("ElderMetro:Trip"))
    }
    @Suppress("DEPRECATION")
    @Test fun installedPermissionsCannotControlPhoneOrOtherApps() {
        val allowed = setOf("INTERNET","ACCESS_NETWORK_STATE","ACCESS_COARSE_LOCATION","ACCESS_FINE_LOCATION","POST_NOTIFICATIONS","VIBRATE","WAKE_LOCK","FOREGROUND_SERVICE","FOREGROUND_SERVICE_LOCATION","FOREGROUND_SERVICE_SPECIAL_USE","RECEIVE_BOOT_COMPLETED").map { "android.permission.$it" }.toSet() + "com.local.eldermetro.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        val requested = app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertEquals("Unexpected capability added to the merged APK",emptySet<String>(),requested - allowed)
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(app.packageName)
        assertTrue(app.packageManager.queryIntentActivities(homeIntent,0).isEmpty())
        val info = app.packageManager.getPackageInfo(app.packageName,PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
        assertFalse(info.services.orEmpty().single { it.name == TripTrackingService::class.java.name }.exported)
        assertFalse(info.receivers.orEmpty().single { it.name == StopTripReceiver::class.java.name }.exported)
    }
    @Test fun defaultReminderDoesNotChangeVolumeModeOrDnd() {
        val audio = app.getSystemService(AudioManager::class.java)
        val manager = app.getSystemService(NotificationManager::class.java)
        val streams = listOf(AudioManager.STREAM_MUSIC,AudioManager.STREAM_RING,AudioManager.STREAM_VOICE_CALL,AudioManager.STREAM_ALARM)
        val volumes = streams.map(audio::getStreamVolume)
        val mode = audio.mode; val ringer = audio.ringerMode; val dnd = manager.currentInterruptionFilter
        startTimer()
        waitUntil { runBlocking { store.data.first().trip.reminderNotified } }
        assertEquals(volumes,streams.map(audio::getStreamVolume)); assertEquals(mode,audio.mode); assertEquals(ringer,audio.ringerMode); assertEquals(dnd,manager.currentInterruptionFilter)
        val channel = manager.getNotificationChannel(TripNotificationManager.ALERT_CHANNEL)
        assertNull(channel.sound); assertFalse(channel.canBypassDnd())
        assertNull(manager.activeNotifications.first { it.id == TripNotificationManager.ALERT }.notification.fullScreenIntent)
    }
}
