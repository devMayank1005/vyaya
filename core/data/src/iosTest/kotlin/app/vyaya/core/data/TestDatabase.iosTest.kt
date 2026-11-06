package app.vyaya.core.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

actual fun inMemoryDatabase(): VyayaDatabase =
    Room.inMemoryDatabaseBuilder<VyayaDatabase>().setDriver(BundledSQLiteDriver()).build()
