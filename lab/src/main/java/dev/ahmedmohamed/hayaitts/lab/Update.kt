package dev.ahmedmohamed.hayaitts.lab

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/** Self-update from the lab server: compare APK hashes, then install the new one through PackageInstaller. */
object Update {
    /** The HayaiTTS app, which the lab can install and hand voice bundles to. */
    const val MAIN = "dev.ahmedmohamed.hayaitts"

    /** The last install's outcome, for the open lab to show (set by [UpdateReceiver]). */
    var result by mutableStateOf<String?>(null)

    /** SHA-256 of the APK this app was installed from. */
    fun installedSha256(context: Context): String = sha256(File(context.packageCodePath))

    /** When HayaiTTS was last installed or updated (epoch seconds), or null if it isn't installed. */
    fun mainInstalledAt(context: Context): Long? =
        runCatching { context.packageManager.getPackageInfo(MAIN, 0).lastUpdateTime / 1000 }.getOrNull()

    /** Opens [bundle] in HayaiTTS's import screen, with read access to that one file only. */
    fun sendToMain(context: Context, bundle: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", bundle)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/x-tar").setPackage(MAIN)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Hands [apk] to the system installer; Android asks the user to confirm (via [UpdateReceiver]). */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(id).use { session ->
            session.openWrite("lab.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // The installer adds its status to this intent, so it has to be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val done = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(done.intentSender)
        }
    }
}
