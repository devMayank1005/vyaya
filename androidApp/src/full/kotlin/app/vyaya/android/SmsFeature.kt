package app.vyaya.android

import android.Manifest
import android.content.Context
import kotlin.time.Clock

/** The full build: SMS as they arrive (RECEIVE_SMS) and the 30-day import (READ_SMS). */
object SmsFeature {
    const val AVAILABLE = true
    val permissions: Array<String> = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
    suspend fun import(context: Context): Int = SmsImport.forApp(context).run(Clock.System.now())
}
