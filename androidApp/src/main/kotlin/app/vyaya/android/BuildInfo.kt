package app.vyaya.android

import android.content.Context

/** The app's version, as written into backups (ExportMeta.appVersion). */
object BuildInfo {
    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
}
