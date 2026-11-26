package app.vyaya.android

import android.Manifest
import android.content.Context
import kotlin.time.Clock

/** The full build: SMS as they arrive (RECEIVE_SMS) and the 30-day import (READ_SMS). */
object SmsFeature {

    /** Widget report FLAW-004: a tablet or Chromebook without a phone radio installs it, but has no SMS to offer. */
    fun available(context: Context): Boolean = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEPHONY)
    val permissions: Array<String> = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
    suspend fun import(context: Context): Imported = SmsImport.forApp(context).run(Clock.System.now())
}
