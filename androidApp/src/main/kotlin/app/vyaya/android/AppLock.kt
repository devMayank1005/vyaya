package app.vyaya.android

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.vyaya.shared.AppLock
import app.vyaya.shared.Authenticator

/** The app lock's state on Android, kept in preferences (excluded from backups and transfers). */
fun appLockOf(context: Context): AppLock {
    val prefs = context.applicationContext.getSharedPreferences("lock", Context.MODE_PRIVATE)
    return AppLock({ prefs.getBoolean(it, false) }, { k, v -> prefs.edit().putBoolean(k, v).apply() }, { System.currentTimeMillis() })
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
