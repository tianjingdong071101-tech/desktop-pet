package com.petpal.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 开机自启 —— 用户需要在设置里打开这个功能
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("pet_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_start", false) && Settings.canDrawOverlays(context)) {
            val serviceIntent = Intent(context, FloatingPetService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
