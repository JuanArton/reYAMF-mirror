package com.mja.reyamf.manager.broadcastreceiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mja.reyamf.common.gson
import com.mja.reyamf.common.model.Config
import com.mja.reyamf.manager.services.YAMFManagerProxy
import com.mja.reyamf.manager.sidebar.Action
import com.mja.reyamf.manager.sidebar.SidebarMenuService

class BootReceiver : BroadcastReceiver() {

    lateinit var config: Config

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            config = gson.fromJson(YAMFManagerProxy.configJson, Config::class.java)

            if (config.launchSideBarAtBoot) {
                Intent(context, SidebarMenuService::class.java).also {
                    it.action = Action.START.name
                    Log.d("reYAMF", "Starting the service in >=26 Mode from a BroadcastReceiver")
                    context.startForegroundService(it)
                }
            }
        }
    }
}