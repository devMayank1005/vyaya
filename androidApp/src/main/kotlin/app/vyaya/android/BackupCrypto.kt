package app.vyaya.android

import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** An encrypted backup that can't be read: wrong password, damaged or cut off, or not supported. Nothing is restored. */
class EncryptedBackupException(message: String) : IOException(message)

/**
 * Password-encrypted backups, docs/encrypted-backup-v1.md (SEC-002 of the MVP report): chunked AES-256-GCM with the
 * header as associated data and the chunk index and a final flag in each nonce, so the file can't be changed,
 * reordered or cut without being refused; the key from PBKDF2-HMAC-SHA256. Streams: a chunk at a time in memory.
 */
object BackupCrypto {
    const val ITERATIONS = 600_000
    const val CHUNK = 65_536
    private val MAGIC = "VYAYAENC".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val KDF_PBKDF2_SHA256: Byte = 1
    private const val HEADER = 41
    private const val TAG = 16
    private val ITERATIONS_ACCEPTED = 1_000..10_000_000
    private val CHUNK_ACCEPTED = 16..1_048_576

    /** Whether a file starting with [head] (its first 8 bytes or more) is an encrypted backup. */
    fun isEncrypted(head: ByteArray): Boolean = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    /** Writes [out] encrypted; closing the returned stream writes the last chunk (and closes [out]). */
    fun encryptingStream(
        out: OutputStream, password: CharArray, iterations: Int = ITERATIONS, chunk: Int = CHUNK,
        salt: ByteArray = random(16), prefix: ByteArray = random(7),
    ): OutputStream {
        require(iterations in ITERATIONS_ACCEPTED && chunk in CHUNK_ACCEPTED && salt.size == 16 && prefix.size == 7)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION).put(KDF_PBKDF2_SHA256).putInt(iterations).put(salt).put(prefix).putInt(chunk).array()
        val key = key(password, salt, iterations)
        out.write(header)
        return object : FilterOutputStream(out) {
            private val buffer = ByteArray(chunk)
            private var filled = 0
            private var index = 0
            private var closed = false

            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                var at = off; var left = len
                while (left > 0) {
                    if (filled == chunk) emit(final = false)
                    val n = minOf(left, chunk - filled)
                    System.arraycopy(b, at, buffer, filled, n)
                    filled += n; at += n; left -= n
                }
            }

            private fun emit(final: Boolean) {
                out.write(seal(key, prefix, index++, final, header, buffer.copyOf(filled)))
                filled = 0
            }

            override fun close() {
                if (closed) return
                closed = true
                if (filled == chunk) emit(final = false) // a full chunk is never the last one
                emit(final = true) // always shorter than a full chunk, possibly empty
                out.close()
            }
        }
    }

    /** Reads an encrypted backup as its plain text; any problem is an [EncryptedBackupException] (an IOException). */
    fun decryptingStream(input: InputStream, password: CharArray): InputStream {
        val header = readUpTo(input, HEADER)
        if (header.size < HEADER) throw EncryptedBackupException("The file was cut off (no complete header)")
        if (!isEncrypted(header)) throw EncryptedBackupException("This is not an encrypted Vyaya backup")
        val b = ByteBuffer.wrap(header, MAGIC.size, HEADER - MAGIC.size)
        val version = b.get(); val kdf = b.get(); val iterations = b.getInt()
        val salt = ByteArray(16).also { b.get(it) }; val prefix = ByteArray(7).also { b.get(it) }; val chunk = b.getInt()
        if (version != VERSION || kdf != KDF_PBKDF2_SHA256 || iterations !in ITERATIONS_ACCEPTED || chunk !in CHUNK_ACCEPTED) {
            throw EncryptedBackupException("This encrypted backup is not supported (made by a newer Vyaya, or damaged)")
        }
        val key = key(password, salt, iterations)
        return object : InputStream() {
            private var plain = ByteArray(0)
            private var at = 0
            private var index = 0
            private var done = false

            private fun next(): Boolean {
                if (done) return false
                val sealed = readUpTo(input, chunk + TAG)
                if (sealed.size < TAG) throw EncryptedBackupException("The file was cut off")
                val final = sealed.size < chunk + TAG
                plain = open(key, prefix, index, final, header, sealed)
                at = 0
                index++
                if (final) {
                    if (input.read() != -1) throw EncryptedBackupException("The file is damaged (data after its end)")
                    done = true
                }
                return true
            }

            override fun read(): Int {
                while (at >= plain.size) if (!next()) return -1
                return plain[at++].toInt() and 0xFF
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (at >= plain.size) if (!next()) return -1
                val n = minOf(len, plain.size - at)
                System.arraycopy(plain, at, b, off, n)
                at += n
                return n
            }

            override fun close() = input.close()

            private fun open(key: SecretKeySpec, prefix: ByteArray, index: Int, final: Boolean, aad: ByteArray, sealed: ByteArray): ByteArray = try {
                cipher(Cipher.DECRYPT_MODE, key, prefix, index, final, aad).doFinal(sealed)
            } catch (e: AEADBadTagException) {
                throw EncryptedBackupException(
                    if (index == 0) "Wrong password, or the file is damaged" else "The file is damaged or was cut off (part ${index + 1})",
                )
            }
        }
    }

    private fun seal(key: SecretKeySpec, prefix: ByteArray, index: Int, final: Boolean, aad: ByteArray, plain: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, prefix, index, final, aad).doFinal(plain)

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, index: Int, final: Boolean, aad: ByteArray): Cipher {
        val nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).put(if (final) 1 else 0).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(TAG * 8, nonce)); updateAAD(aad) }
    }

    /**
     * PBKDF2-HMAC-SHA256 over the password's UTF-8 bytes (RFC 8018). Done here rather than by a provider's PBKDF2,
     * which differ in how they turn a password's characters into bytes; the spec says UTF-8, and iOS must match.
     */
    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val bytes = Charsets.UTF_8.encode(CharBuffer.wrap(password)).let { buf -> ByteArray(buf.remaining()).also { buf.get(it) } }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(bytes, "HmacSHA256")) }
        bytes.fill(0)
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1)) // block 1: 32 bytes is one SHA-256 block
        var u = mac.doFinal()
        val t = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        return SecretKeySpec(t, "AES").also { t.fill(0) }
    }

    private fun readUpTo(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) break
            got += r
        }
        return if (got == n) buf else buf.copyOf(got)
    }

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
}

