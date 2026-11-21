package app.vyaya.android

import android.content.Context

/** The Play build reads no SMS. */
object SmsFeature {
    const val AVAILABLE = false
    @Suppress("UNUSED_PARAMETER")
    fun available(context: Context): Boolean = false
    val permissions: Array<String> = emptyArray()
    @Suppress("UNUSED_PARAMETER")
    suspend fun import(context: Context): Int = 0
}
