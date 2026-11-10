package jp.tenreader.launcher;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Separate translucent task: the app underneath remains the source, not ten's settings. */
public final class CaptureActivity extends Activity {
    static final String TICKET="ticket";
    private static final int REQUEST=71;
    private long ticket;
    private boolean delivered;
    static void open(Context context) {
        if (!Settings.canDrawOverlays(context) || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            Toast.makeText(context,"画面ロックを解除し、tenの重ね表示を許可してください。",Toast.LENGTH_LONG).show();return;
        }
        long ticket=CaptureTicket.begin();
        context.stopService(new Intent(context,ScreenReaderService.class));
        OverlayService.refreshVisibility();
        try { context.startActivity(new Intent(context,CaptureActivity.class).putExtra(TICKET,ticket)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK)); }
        catch (RuntimeException e) { CaptureTicket.end(ticket);OverlayService.refreshVisibility();Toast.makeText(context,"画面取得を開始できませんでした。",Toast.LENGTH_LONG).show(); }
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);ticket=getIntent().getLongExtra(TICKET,0);
        if (!CaptureTicket.owns(ticket)) { finishAndRemoveTask();return; }
        if (state==null) {
            try { startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),REQUEST); }
            catch (RuntimeException e) { finishAndRemoveTask(); }
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request!=REQUEST)return;
        if (result==RESULT_OK && data!=null && CaptureTicket.owns(ticket) && !getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            try {
                startForegroundService(new Intent(this,ScreenReaderService.class).setAction(ScreenReaderService.START)
                    .putExtra(TICKET,ticket).putExtra(ScreenReaderService.GRANT,data));
                delivered=true;
            } catch (RuntimeException e) { Toast.makeText(this,"画面取得を開始できませんでした。再試行してください。",Toast.LENGTH_LONG).show(); }
        } else Toast.makeText(this,"画面の読み取りを中止しました。",Toast.LENGTH_SHORT).show();
        finishAndRemoveTask();
    }
    @Override public void onDestroy() {
        if (!isChangingConfigurations()) {
            if (delivered && CaptureTicket.owns(ticket)) {
                // Start the actual capture only after our permission Activity is removed.
                try { startService(new Intent(this,ScreenReaderService.class).setAction(ScreenReaderService.READY).putExtra(TICKET,ticket)); }
                catch(RuntimeException e){CaptureTicket.end(ticket);OverlayService.refreshVisibility();}
            } else { CaptureTicket.end(ticket);OverlayService.refreshVisibility(); }
        }
        super.onDestroy();
    }
}
