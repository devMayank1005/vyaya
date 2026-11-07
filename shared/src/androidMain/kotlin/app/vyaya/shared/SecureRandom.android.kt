package app.vyaya.shared

import kotlin.random.Random
import kotlin.random.asKotlinRandom

actual fun secureRandom(): Random = java.security.SecureRandom().asKotlinRandom()
