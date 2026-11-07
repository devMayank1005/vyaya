package app.vyaya.android

import app.vyaya.shared.BackupCryptoException
import app.vyaya.shared.EncryptedBackup
import app.vyaya.shared.JavaxPrimitives
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/** An encrypted backup that can't be read: wrong password, damaged or cut off, or not supported. Nothing is restored. */
class EncryptedBackupException(message: String) : IOException(message)

/**
 * Encrypted backups on Android as Java streams: the format itself is shared with iOS ([EncryptedBackup],
 * docs/encrypted-backup-v1.md); Android supplies the primitives ([JavaxPrimitives]).
 */
object BackupCrypto {
    const val ITERATIONS = EncryptedBackup.ITERATIONS
    const val CHUNK = EncryptedBackup.CHUNK

    fun isEncrypted(head: ByteArray): Boolean = EncryptedBackup.isEncrypted(head)

    /** Writes [out] encrypted; closing the returned stream writes the last chunk (and closes [out]). */
    fun encryptingStream(
        out: OutputStream, password: CharArray, iterations: Int = ITERATIONS, chunk: Int = CHUNK,
        salt: ByteArray = random(16), prefix: ByteArray = random(7),
    ): OutputStream {
        val writer = EncryptedBackup.Writer(JavaxPrimitives, password, iterations, chunk, salt, prefix) { out.write(it) }
        return object : OutputStream() {
            override fun write(b: Int) = writer.write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray, off: Int, len: Int) = writer.write(b, off, len)
            override fun close() { writer.close(); out.close() }
        }
    }

    /** Reads an encrypted backup as its plain text; any problem is an [EncryptedBackupException] (an IOException). */
    fun decryptingStream(input: InputStream, password: CharArray): InputStream {
        val reader = guarded { EncryptedBackup.Reader(JavaxPrimitives, password) { b, off, len -> input.read(b, off, len) } }
        return object : InputStream() {
            override fun read(): Int = ByteArray(1).let { b -> if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
            override fun read(b: ByteArray, off: Int, len: Int): Int = guarded { reader.read(b, off, len) }
            override fun close() = input.close()
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (e: BackupCryptoException) { throw EncryptedBackupException(e.message ?: "unreadable") }

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
}
