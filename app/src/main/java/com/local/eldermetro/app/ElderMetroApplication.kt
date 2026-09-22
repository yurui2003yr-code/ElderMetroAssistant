package com.local.eldermetro.app
import android.app.Application
class ElderMetroApplication : Application() {
    val container by lazy { AppContainer(this) }
    var activityResumed = false
        private set
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) { activityResumed = true }
            override fun onActivityPaused(activity: android.app.Activity) { activityResumed = false }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
    }
}
