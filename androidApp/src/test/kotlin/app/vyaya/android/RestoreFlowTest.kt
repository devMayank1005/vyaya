package app.vyaya.android

import app.vyaya.core.backup.DecodeResult
import app.vyaya.core.backup.ExportMeta
import app.vyaya.core.backup.RestoreMode
import app.vyaya.core.data.RestoreResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.time.Instant

/** MVP FLAW-006: "Restore anyway" when the picked file is no longer at hand asks for it again. */
class RestoreFlowTest {

    private val now = Instant.parse("2026-11-06T10:00:00Z")
    private val calls = mutableListOf<Pair<RestoreMode, Boolean>>()
    private var picks = 0
    private val restore: suspend (InputStream, RestoreMode, Boolean, CharArray?) -> RestoreResult = { _, mode, anyway, _ ->
        calls += mode to anyway
        if (anyway) RestoreResult.Restored(ExportMeta("1", "android", now)) else RestoreResult.Refused(DecodeResult.Failure(listOf("clock"), clockBehind = true))
    }
    private fun flow(open: (String) -> InputStream? = { ByteArrayInputStream(ByteArray(1)) }) =
        RestoreFlow(open = open, pick = { picks++ }, restore = restore)

    @Test
    fun restore_anyway_uses_the_file_already_picked() = runTest {
        val f = flow()
        f.start(RestoreMode.REPLACE)
        assertEquals(1, picks)
        f.picked("file-1")
        f.restoreAnyway()
        assertEquals(listOf(RestoreMode.REPLACE to false, RestoreMode.REPLACE to true), calls)
    }

    @Test
    fun without_the_file_it_asks_for_it_again_and_restores_anyway() = runTest {
        val fresh = flow() // the app was closed: nothing remembered
        assertNull(fresh.restoreAnyway())
        assertEquals(1, picks)
        assertEquals(true, fresh.picked("file-1") is RestoreResult.Restored)
        assertEquals(listOf(RestoreMode.MERGE to true), calls)
    }

    @Test
    fun a_file_that_cant_be_opened_any_more_is_asked_for_again() = runTest {
        var gone = false
        val f = flow(open = { if (gone) null else ByteArrayInputStream(ByteArray(1)) })
        f.start(RestoreMode.MERGE); f.picked("file-1")
        gone = true
        assertNull(f.restoreAnyway())
        assertEquals(2, picks)
    }

    @Test
    fun a_password_is_used_on_the_same_file_and_kept_for_restore_anyway() = runTest {
        val seen = mutableListOf<String?>()
        val f = RestoreFlow<String>(open = { ByteArrayInputStream(ByteArray(1)) }, pick = { picks++ }, restore = { _, _, anyway, pw ->
            seen += pw?.concatToString()
            if (pw == null) RestoreResult.Refused(DecodeResult.Failure(listOf(BackupFiles.NEEDS_PASSWORD)))
            else if (!anyway) RestoreResult.Refused(DecodeResult.Failure(listOf("clock"), clockBehind = true))
            else RestoreResult.Restored(ExportMeta("1", "android", now))
        })
        f.start(RestoreMode.MERGE); f.picked("file")
        assertEquals(true, f.withPassword("secret pw".toCharArray()) is RestoreResult.Refused)
        assertEquals(true, f.restoreAnyway() is RestoreResult.Restored)
        assertEquals(listOf(null, "secret pw", "secret pw"), seen)
    }
}
