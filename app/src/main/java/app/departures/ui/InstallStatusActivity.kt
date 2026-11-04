package app.departures.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import app.departures.data.Updater

/**
 * Invisible, non-exported activity that receives Android's installer status for an in-app
 * update. Only the installer (through our own PendingIntent) can start it, so the confirmation
 * intent it launches can be trusted. It shows nothing and finishes immediately.
 */
class InstallStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Handle each status once: not again if Android recreates this activity.
        if (savedInstanceState == null) handle(intent)
        finish()
    }

    private fun handle(intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        Updater.outcomeFor(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
            ?.let { Updater(applicationContext).recordOutcome(it) }
    }
}
