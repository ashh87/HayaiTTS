package dev.ahmedmohamed.hayaitts.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** PackageInstaller's answer to [Update.install]: show its confirmation screen, or say why it failed. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            // the lab itself restarts as the new version; for HayaiTTS, tell the open lab
            PackageInstaller.STATUS_SUCCESS -> Update.result = "HayaiTTS updated"
            else -> {
                val why = "Update failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
                Update.result = why
                Toast.makeText(context, why, Toast.LENGTH_LONG).show()
            }
        }
    }
}
