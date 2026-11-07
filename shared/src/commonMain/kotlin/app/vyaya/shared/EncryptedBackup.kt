package app.vyaya.shared

/** The platform's primitives for encrypted backups (javax on Android; CommonCrypto and CryptoKit on iOS). */
interface BackupCryptoPrimitives {
    /** PBKDF2-HMAC-SHA256 (RFC 8018), 32 bytes. */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int): ByteArray

    /** AES-256-GCM: ciphertext followed by the 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray

    /** The plain text, or null when the tag doesn't verify. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?
}

/** An encrypted backup that can't be read: wrong password, damaged or cut off, or not supported. Nothing is restored. */
class BackupCryptoException(message: String) : RuntimeException(message)

/**
 * Password-encrypted backups, docs/encrypted-backup-v1.md (SEC-002 of the MVP report), written once for both apps:
 * chunked AES-256-GCM with the header as associated data and the chunk index and a final flag in each nonce, so a
 * file can't be changed, reordered or cut without being refused; the key from PBKDF2-HMAC-SHA256. A chunk at a time.
 */
object EncryptedBackup {
    const val ITERATIONS = 600_000
    const val CHUNK = 65_536
    private val MAGIC = "VYAYAENC".encodeToByteArray()
    private const val VERSION: Byte = 1
    private const val KDF_PBKDF2_SHA256: Byte = 1
    const val HEADER = 41
    private const val TAG = 16
    private val ITERATIONS_ACCEPTED = 1_000..10_000_000
    private val CHUNK_ACCEPTED = 16..1_048_576

    /** Whether a file starting with [head] (its first 8 bytes or more) is an encrypted backup. */
    fun isEncrypted(head: ByteArray): Boolean = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    /** Writes the header at once, then chunks as data comes; [close] writes the last chunk. Bytes go to [sink]. */
    class Writer(
        private val crypto: BackupCryptoPrimitives, password: CharArray, iterations: Int = ITERATIONS, private val chunk: Int = CHUNK,
        salt: ByteArray, private val prefix: ByteArray, private val sink: (ByteArray) -> Unit,
    ) {
        private val header: ByteArray
        private val key: ByteArray
        private val buffer = ByteArray(chunk)
        private var filled = 0
        private var index = 0
        private var closed = false

        init {
            require(iterations in ITERATIONS_ACCEPTED && chunk in CHUNK_ACCEPTED && salt.size == 16 && prefix.size == 7)
            header = MAGIC + byteArrayOf(VERSION, KDF_PBKDF2_SHA256) + int(iterations) + salt + prefix + int(chunk)
            key = derive(crypto, password, salt, iterations)
            sink(header)
        }

        fun write(b: ByteArray, off: Int = 0, len: Int = b.size) {
            check(!closed)
            var at = off; var left = len
            while (left > 0) {
                if (filled == chunk) emit(final = false)
                val n = minOf(left, chunk - filled)
                b.copyInto(buffer, filled, at, at + n)
                filled += n; at += n; left -= n
            }
        }

        private fun emit(final: Boolean) {
            sink(crypto.seal(key, nonce(prefix, index++, final), header, buffer.copyOf(filled)))
            filled = 0
        }

        fun close() {
            if (closed) return
            closed = true
            if (filled == chunk) emit(final = false) // a full chunk is never the last one
            emit(final = true) // always shorter than a full chunk, possibly empty
            key.fill(0)
        }
    }

    /**
     * Reads an encrypted backup as its plain text, pulling bytes from [source] (`read(buffer, offset, length)`, -1 at
     * the end). Any problem is a [BackupCryptoException].
     */
    class Reader(private val crypto: BackupCryptoPrimitives, password: CharArray, private val source: (ByteArray, Int, Int) -> Int) {
        private val header = readUpTo(HEADER)
        private val prefix: ByteArray
        private val chunk: Int
        private val key: ByteArray
        private var plain = ByteArray(0)
        private var at = 0
        private var index = 0
        private var done = false

        init {
            if (header.size < HEADER) throw BackupCryptoException("The file was cut off (no complete header)")
            if (!isEncrypted(header)) throw BackupCryptoException("This is not an encrypted Vyaya backup")
            val version = header[8]; val kdf = header[9]; val iterations = int(header, 10)
            val salt = header.copyOfRange(14, 30)
            prefix = header.copyOfRange(30, 37)
            chunk = int(header, 37)
            if (version != VERSION || kdf != KDF_PBKDF2_SHA256 || iterations !in ITERATIONS_ACCEPTED || chunk !in CHUNK_ACCEPTED) {
                throw BackupCryptoException("This encrypted backup is not supported (made by a newer Vyaya, or damaged)")
            }
            key = derive(crypto, password, salt, iterations)
        }

        /** Like InputStream.read: the bytes read, or -1 at the end. */
        fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at >= plain.size) if (!next()) return -1
            val n = minOf(len, plain.size - at)
            plain.copyInto(b, off, at, at + n)
            at += n
            return n
        }

        private fun next(): Boolean {
            if (done) return false
            val sealed = readUpTo(chunk + TAG)
            if (sealed.size < TAG) throw BackupCryptoException("The file was cut off")
            val final = sealed.size < chunk + TAG
            plain = crypto.open(key, nonce(prefix, index, final), header, sealed) ?: throw BackupCryptoException(
                if (index == 0) "Wrong password, or the file is damaged" else "The file is damaged or was cut off (part ${index + 1})",
            )
            at = 0
            index++
            if (final) {
                if (source(ByteArray(1), 0, 1) > 0) throw BackupCryptoException("The file is damaged (data after its end)")
                done = true
                key.fill(0)
            }
            return true
        }

        private fun readUpTo(n: Int): ByteArray {
            val buf = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = source(buf, got, n - got)
                if (r < 0) break
                got += r
            }
            return if (got == n) buf else buf.copyOf(got)
        }
    }

    private fun nonce(prefix: ByteArray, index: Int, final: Boolean) = prefix + int(index) + byteArrayOf(if (final) 1 else 0)

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun int(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    /** The key from the password's UTF-8 bytes (made from the characters directly, never a String, and wiped after). */
    private fun derive(crypto: BackupCryptoPrimitives, password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val bytes = utf8(password)
        try { return crypto.pbkdf2(bytes, salt, iterations) } finally { bytes.fill(0) }
    }

    internal fun utf8(chars: CharArray): ByteArray {
        val out = ArrayList<Byte>(chars.size * 3)
        var i = 0
        while (i < chars.size) {
            var cp = chars[i].code
            if (cp in 0xD800..0xDBFF && i + 1 < chars.size && chars[i + 1].code in 0xDC00..0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) shl 10) + (chars[i + 1].code - 0xDC00); i++
            } else if (cp in 0xD800..0xDFFF) cp = 0xFFFD // a lone surrogate, as Java's encoder does
            when {
                cp < 0x80 -> out += cp.toByte()
                cp < 0x800 -> { out += (0xC0 or (cp shr 6)).toByte(); out += (0x80 or (cp and 0x3F)).toByte() }
                cp < 0x10000 -> { out += (0xE0 or (cp shr 12)).toByte(); out += (0x80 or (cp shr 6 and 0x3F)).toByte(); out += (0x80 or (cp and 0x3F)).toByte() }
                else -> {
                    out += (0xF0 or (cp shr 18)).toByte(); out += (0x80 or (cp shr 12 and 0x3F)).toByte()
                    out += (0x80 or (cp shr 6 and 0x3F)).toByte(); out += (0x80 or (cp and 0x3F)).toByte()
                }
            }
            i++
        }
        return out.toByteArray()
    }
}
