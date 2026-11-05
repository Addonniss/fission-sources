package dev.infusion.tv.updater

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import dev.infusion.core.update.UpdateFailureKind
import java.util.concurrent.atomic.AtomicReference

object UpdateInstallResults {

    private val listener = AtomicReference<((InstallOutcome) -> Unit)?>(null)

    private const val EXTRA_SESSION_ID = "dev.infusion.tv.updater.SESSION_ID"

    fun attach(onOutcome: (InstallOutcome) -> Unit) {
        listener.set(onOutcome)
    }

    fun detach() {
        listener.set(null)
    }

    fun pendingIntent(context: Context, sessionId: Int): PendingIntent {
        val intent = Intent(context, UpdateInstallReceiver::class.java)
            .putExtra(EXTRA_SESSION_ID, sessionId)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
    }

    fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {

                val confirmation = confirmationIntent(intent)
                if (confirmation != null) {
                    runCatching {
                        context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> deliver(InstallOutcome.Installed)
            PackageInstaller.STATUS_FAILURE_ABORTED -> deliver(InstallOutcome.UserCancelled)
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                deliver(InstallOutcome.Failed(UpdateFailureKind.INSUFFICIENT_STORAGE))
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                deliver(InstallOutcome.Failed(UpdateFailureKind.SIGNER_MISMATCH))
            else -> deliver(InstallOutcome.Failed(UpdateFailureKind.INSTALL_FAILED))
        }
    }

    private fun deliver(outcome: InstallOutcome) {
        listener.get()?.invoke(outcome)
    }

    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
}

class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        UpdateInstallResults.onReceive(context, intent)
    }
}
