package app.vyaya.shared

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUserDefaults
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Face ID, Touch ID or the device passcode (LocalAuthentication, deviceOwnerAuthentication). A device without a
 * passcode has nothing to ask, so it is allowed, as on Android (the screen says so next to the lock switch).
 */
class IosAuthenticator : Authenticator {
    @OptIn(ExperimentalForeignApi::class)
    override fun authenticate(reason: String, done: (Boolean) -> Unit) {
        val context = LAContext()
        if (!context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)) return done(true)
        context.evaluatePolicy(LAPolicyDeviceOwnerAuthentication, localizedReason = reason) { ok, _ ->
            dispatch_async(dispatch_get_main_queue()) { done(ok) }
        }
    }

    companion object {
        @OptIn(ExperimentalForeignApi::class)
        fun deviceSecure(): Boolean = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error = null)
    }
}

/** The app lock's state on iOS, in the app's own defaults on this device. */
fun iosAppLock(): AppLock {
    val defaults = NSUserDefaults.standardUserDefaults
    return AppLock({ defaults.boolForKey(it) }, { k, v -> defaults.setBool(v, forKey = k) }, { kotlin.time.Clock.System.now().toEpochMilliseconds() })
}
