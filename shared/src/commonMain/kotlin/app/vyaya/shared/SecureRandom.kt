package app.vyaya.shared

import kotlin.random.Random

/** Random numbers from the platform's cryptographic source (passphrases). */
expect fun secureRandom(): Random
