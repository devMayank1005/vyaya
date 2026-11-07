package app.vyaya.android

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Asks the user to confirm it's them (fingerprint, face, or the phone's PIN, pattern or password). */
fun interface Authenticator {
    fun authenticate(reason: String, done: (Boolean) -> Unit)
}

/**
 * The app lock (SEC-003 of reports/android-mvp-2026-11-06.md). Optional and off unless the user turns it on (the plan's
 * "optional app lock"): then Vyaya locks when it starts and when it was away for more than 30 seconds. Whether it is
 * on is kept on this phone only (preferences, excluded from backups and transfers).
 */
class AppLock(private val get: (String) -> Boolean, private val set: (String, Boolean) -> Unit, private val clockMs: () -> Long) {

    var enabled: Boolean
        get() = get(KEY)
        set(value) = set(KEY, value)

    var locked: Boolean = false
        private set

    /** When the app last went to the background; null in a new process (the phone may have closed it). */
    private var stoppedAt: Long? = null

    fun onStart() {
        if (enabled && stoppedAt.let { it == null || clockMs() - it > GRACE_MS }) locked = true
    }

    fun onStop() { stoppedAt = clockMs() }

    fun unlocked() { locked = false }

    companion object {
        const val GRACE_MS = 30_000L
        private const val KEY = "app_lock"

        /** Export and restore move or replace everything: they always ask first (when the phone has a screen lock). */
        fun confirmThen(authenticator: Authenticator, reason: String, action: () -> Unit) =
            authenticator.authenticate(reason) { ok -> if (ok) action() }

        fun of(context: Context): AppLock {
            val prefs = context.applicationContext.getSharedPreferences("lock", Context.MODE_PRIVATE)
            return AppLock({ prefs.getBoolean(it, false) }, { k, v -> prefs.edit().putBoolean(k, v).apply() }, { System.currentTimeMillis() })
        }
    }
}

/**
 * The phone's own check: biometrics (weak is enough to unlock an app) or the screen lock's PIN, pattern or password.
 * A phone without a screen lock has nothing to ask, so it is allowed (anyone holding it can open the phone anyway).
 */
class BiometricAuthenticator(private val activity: FragmentActivity) : Authenticator {
    override fun authenticate(reason: String, done: (Boolean) -> Unit) {
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceSecure != true) return done(true)
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = done(false)
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Vyaya").setSubtitle(reason)
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL).build(),
        )
    }
}
