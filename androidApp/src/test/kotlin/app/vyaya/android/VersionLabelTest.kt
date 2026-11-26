package app.vyaya.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.vyaya.core.backup.BackupCodec
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Backlog 2026-11-26 #6: the app's own version name is a label its backups may carry (both flavors run this). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VersionLabelTest {
    @Test
    fun backlog_flaw_005_this_builds_version_name_passes_the_backup_label_rule() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = BuildInfo.versionName(context)
        assertTrue("'$name' would make this app refuse its own backups", BackupCodec.isPlainLabel(name))
        assertTrue(BackupCodec.isPlainLabel("android") && BackupCodec.isPlainLabel("ios"))
    }
}
