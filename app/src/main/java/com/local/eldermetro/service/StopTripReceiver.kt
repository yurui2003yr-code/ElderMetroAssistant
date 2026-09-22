package com.local.eldermetro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.eldermetro.app.ElderMetroApplication
import com.local.eldermetro.domain.TripState
import kotlinx.coroutines.*

class StopTripReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        context.stopService(Intent(context,TripTrackingService::class.java))
        TripStateRestorer.cancel(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { (context.applicationContext as ElderMetroApplication).container.store.updateTrip { TripState() }; TripStateRestorer.cancel(context) }
            finally { pending.finish() }
        }
    }
    companion object { const val ACTION = "com.local.eldermetro.STOP_TRIP" }
}
