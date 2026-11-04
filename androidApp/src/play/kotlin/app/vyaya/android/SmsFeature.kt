package app.vyaya.android

import android.content.Context

/** The Play build reads no SMS. */
object SmsFeature {
    const val AVAILABLE = false
    val permissions: Array<String> = emptyArray()
    @Suppress("UNUSED_PARAMETER")
    suspend fun import(context: Context): Int = 0
}
