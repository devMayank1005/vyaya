package app.vyaya.android

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import app.vyaya.core.data.CaptureStore
import app.vyaya.core.data.DetectedInbox
import app.vyaya.core.data.VyayaDatabase
import app.vyaya.core.parser.UpiApps

/** The app's single database and the stores on it. */
class VyayaApp : Application() {

    val database: VyayaDatabase by lazy { open(this) }
    val inbox: DetectedInbox by lazy { DetectedInbox(database) }
    val capture: CaptureStore by lazy { CaptureStore(database, inbox) }
    val appVerifier: AppVerifier by lazy { AppVerifier(packageManager, UpiApps.default.apps.associateBy { it.packageName }) }

    companion object {
        /** The system's SQLite (no extra native library; the same one Robolectric tests run on). */
        fun open(context: Context, name: String = "vyaya.db"): VyayaDatabase =
            Room.databaseBuilder<VyayaDatabase>(context, context.getDatabasePath(name).absolutePath)
                .setDriver(AndroidSQLiteDriver())
                .build()
    }
}
