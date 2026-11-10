package jp.tenreader.launcher;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class ReaderTileService extends TileService {
    @Override public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(OverlayService.isRunning() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("ten ボタン");
        tile.setContentDescription(OverlayService.isRunning() ? "tenボタンを非表示にする" : "tenボタンを表示する");
        tile.updateTile();
    }
    @Override public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::openControls);
        else openControls();
    }
    private void openControls() {
        // A visible Activity performs permission requests and any foreground-service start.
        Intent intent = new Intent(this, MainActivity.class).setAction(MainActivity.TOGGLE_OVERLAY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        else startLegacyActivity(intent);
    }

    // PendingIntent overload was added in API 34; earlier OS versions require the Intent overload.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void startLegacyActivity(Intent intent) { startActivityAndCollapse(intent); }
}
