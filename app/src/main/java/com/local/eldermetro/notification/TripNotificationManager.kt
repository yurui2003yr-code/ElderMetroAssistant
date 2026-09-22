package com.local.eldermetro.notification

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.local.eldermetro.MainActivity
import com.local.eldermetro.R
import com.local.eldermetro.domain.TripState
import com.local.eldermetro.service.StopTripReceiver
import com.local.eldermetro.speech.ConsiderateSpeechController

class TripNotificationManager(private val context: Context) {
    companion object { const val RUNNING = 41; const val ALERT = 42; const val TRIP_CHANNEL = "trip_v1"; const val ALERT_CHANNEL = "alight_v2_quiet" }
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel(TRIP_CHANNEL,"行程进行中",NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL,"准备下车提醒",NotificationManager.IMPORTANCE_HIGH).apply { setSound(null,null); setBypassDnd(false); enableVibration(true); vibrationPattern = VibrationController.pattern; lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
    }
    fun enabled(): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled() && manager.getNotificationChannel(ALERT_CHANNEL).importance >= NotificationManager.IMPORTANCE_HIGH && manager.getNotificationChannel(TRIP_CHANNEL).importance != NotificationManager.IMPORTANCE_NONE
    private fun base(channel: String): NotificationCompat.Builder = NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_metro)
        .setContentIntent(PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .addAction(0,"结束行程",PendingIntent.getBroadcast(context,0,Intent(context,StopTripReceiver::class.java).setAction(StopTripReceiver.ACTION),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    fun ongoing(state: TripState): Notification = base(TRIP_CHANNEL).setContentTitle("地铁行程进行中")
        .setContentText("${state.segment?.line.orEmpty()} · 请核对车厢报站").setOngoing(true).setOnlyAlertOnce(true).build()
    @android.annotation.SuppressLint("MissingPermission")
    fun alert(state: TripState) {
        if (!enabled()) return
        val next = state.route?.segments?.getOrNull(state.segmentIndex + 1)?.line
        val text = "预计还有1站到 ${state.segment?.alighting?.name}。请核对车厢报站。" + (next?.let { "下车后换乘$it" } ?: "")
        val inCommunication = ConsiderateSpeechController.communicationActive(context.getSystemService(android.media.AudioManager::class.java))
        manager.notify(ALERT,base(ALERT_CHANNEL).setContentTitle("在${state.segment?.alighting?.name.orEmpty()}下车").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(false).setOnlyAlertOnce(true).setSilent(inCommunication).build())
    }
    fun clear() { manager.cancel(ALERT); VibrationController(context).cancel() }
}
class VibrationController(context: Context) {
    companion object { val pattern = longArrayOf(0,300,200,300,200,600) }
    @Suppress("DEPRECATION") private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    fun cancel() = vibrator.cancel()
}
