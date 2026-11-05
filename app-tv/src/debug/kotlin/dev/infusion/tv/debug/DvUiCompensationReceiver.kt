package dev.infusion.tv.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import dev.infusion.tv.playback.DvUiCompensationExperiment

class DvUiCompensationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!isDebuggable(context)) return
        DvUiCompensationExperiment.apply(intent.getStringExtra(EXTRA_COMMAND))
    }

    companion object {

        const val ACTION: String = "dev.infusion.tv.DEBUG.DV_UI_COMPENSATION"

        const val EXTRA_COMMAND: String = "cmd"

        private fun isDebuggable(context: Context): Boolean =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}
