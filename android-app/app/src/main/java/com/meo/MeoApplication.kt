package com.meo

import android.app.Application
import com.meo.diagnostics.CrashLog

/**
 * Exists only to install the crash handler before anything else can fail.
 *
 * A handler registered in an Activity misses the failures that matter most —
 * anything thrown while a service is being created, or on a background thread
 * during startup — because by then the process may already be on its way down.
 */
class MeoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
