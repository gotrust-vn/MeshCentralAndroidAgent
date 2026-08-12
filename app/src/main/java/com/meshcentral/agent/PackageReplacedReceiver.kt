package com.meshcentral.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val launch = Intent(context, MainActivity::class.java)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
    }
}
