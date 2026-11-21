package app.vyaya.android

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The Quick Settings tile "Add payment" (Phase 3): opens the add form, behind the app lock like any other way in. */
class QuickAddTile : TileService() {

    /** Opens the app and closes the panel (replaced in tests: it needs the real Quick Settings panel). */
    internal var launch: (android.app.PendingIntent) -> Unit = ::collapseAndOpen

    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        // On a locked phone, the phone's own unlock comes first.
        if (isLocked) unlockAndRun(::open) else open()
    }

    private fun open() = launch(QuickAddWidget.addIntent(this))

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun collapseAndOpen(add: android.app.PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startActivityAndCollapse(add)
        else @Suppress("DEPRECATION") startActivityAndCollapse(
            Intent(this, MainActivity::class.java).setAction(QuickAddWidget.ACTION_ADD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
