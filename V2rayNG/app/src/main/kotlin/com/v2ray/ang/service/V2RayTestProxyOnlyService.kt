package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.lang.ref.SoftReference

class V2RayTestProxyOnlyService : Service(), ServiceControl {
    override fun onCreate() {
        super.onCreate()
        V2RayTestServiceManager.serviceControl = SoftReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        V2RayTestServiceManager.startV2rayPoint()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        V2RayTestServiceManager.stopV2rayPoint()
    }

    override fun getService(): Service {
        return this
    }

    override fun startService(parameters: String) {
        // do nothing
    }

    override fun stopService() {
        stopSelf()
    }

    override fun vpnProtect(socket: Int): Boolean {
        return true
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
