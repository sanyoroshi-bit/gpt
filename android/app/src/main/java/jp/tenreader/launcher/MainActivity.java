package jp.tenreader.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    static final String OPEN_READER = "jp.tenreader.launcher.OPEN_READER";
    static final String TOGGLE_OVERLAY = "jp.tenreader.launcher.TOGGLE_OVERLAY";
    private boolean waitingForOverlay;
    private TextView status;
    private Button enable;
    private final BroadcastReceiver serviceState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        waitingForOverlay = state != null && state.getBoolean("waitingForOverlay");
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(245, 240, 231));
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(32));
        scroll.addView(content);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        } else scroll.setFitsSystemWindows(true);
        TextView title = text(content, "ten.", 48);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        text(content, "ほかのアプリから、読書へ。", 20);
        text(content, "試用版 0.1.0 · Android 8以降", 13);
        button(content, "tenを開く ↗", this::openReader);
        status = text(content, "", 15);
        enable = button(content, "画面のボタンを表示", this::enableOverlay);
        button(content, "ボタンを非表示にする", () -> { stopService(new Intent(this, OverlayService.class)); refresh(); });
        text(content, "表示したtenをタップすると、ブラウザでtenが開きます。ドラッグで位置を変え、長押しで非表示にできます。", 15);
        button(content, "クイック設定にボタンを追加", this::addTile);
        text(content, "クイック設定の「ten ボタン」からも表示・非表示を切り替えられます。追加されないときは、画面上端を2回下へスワイプし、編集から追加してください。", 14);
        button(content, "通知の設定", () -> {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            try { startActivity(intent); } catch (ActivityNotFoundException e) { toast("端末の設定からtenの通知を確認してください。"); }
        });
        text(content, "このアプリは起動ボタンです。表示には「ほかのアプリの上に重ねて表示」の許可を使います。画面やクリップボードの読み取りは行いません。文章・記事・画像の取り込みは、開いたtenで操作します。", 14);
        text(content, "OSや省電力設定でボタンが停止することがあります。再起動後は自動表示しません。再びこの画面から表示してください。", 14);
        setContentView(scroll);
        if (state == null) handleIntent(getIntent());
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        String action = intent.getAction();
        // Consume actions so rotation never reopens the browser or toggles twice.
        intent.setAction(null);
        if (OPEN_READER.equals(action)) { openReader(); finish(); }
        else if (TOGGLE_OVERLAY.equals(action)) {
            if (OverlayService.isRunning()) stopService(new Intent(this, OverlayService.class));
            else enableOverlay();
        }
    }

    @Override public void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(OverlayService.STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(serviceState, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerLegacyStateReceiver(filter);
        refresh();
    }

    // Only called below API 33, before RECEIVER_NOT_EXPORTED exists. This receiver only refreshes local state.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLegacyStateReceiver(IntentFilter filter) { registerReceiver(serviceState, filter); }

    @Override public void onStop() {
        unregisterReceiver(serviceState);
        super.onStop();
    }

    @Override public void onResume() {
        super.onResume();
        if (!Settings.canDrawOverlays(this) && OverlayService.isRunning()) stopService(new Intent(this, OverlayService.class));
        if (waitingForOverlay) {
            waitingForOverlay = false;
            if (Settings.canDrawOverlays(this)) startOverlay();
        }
        refresh();
    }

    @Override public void onSaveInstanceState(Bundle out) {
        out.putBoolean("waitingForOverlay", waitingForOverlay);
        super.onSaveInstanceState(out);
    }

    private void enableOverlay() {
        if (Settings.canDrawOverlays(this)) { startOverlay(); return; }
        new AlertDialog.Builder(this).setTitle("tenの起動ボタンを表示")
            .setMessage("次の設定画面で、tenに「ほかのアプリの上に重ねて表示」を許可してください。許可して戻るとボタンを表示します。画面の内容は読み取りません。")
            .setNegativeButton("今はしない", null)
            .setPositiveButton("設定を開く", (dialog, which) -> {
                try {
                    waitingForOverlay = true;
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException e) { waitingForOverlay = false; toast("端末の設定から重ね表示を許可してください。"); }
            }).show();
    }

    private void startOverlay() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1);
        }
        try { startForegroundService(new Intent(this, OverlayService.class)); }
        catch (RuntimeException e) { toast("ボタンを表示できませんでした。重ね表示の許可と端末の設定を確認してください。"); }
    }

    private void refresh() {
        if (status == null) return;
        boolean running = OverlayService.isRunning();
        status.setText(running ? "起動ボタンを表示中です。" : "起動ボタンは非表示です。");
        enable.setEnabled(!running);
    }

    private void openReader() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://gpt-delta.vercel.app/"))
            .addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException e) { toast("ブラウザを利用できません。ブラウザを有効にしてから再試行してください。"); }
    }

    private void addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager.class).requestAddTileService(
                new ComponentName(this, ReaderTileService.class), "ten ボタン",
                Icon.createWithResource(this, R.drawable.ic_tile), getMainExecutor(),
                result -> toast(result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
                    ? "クイック設定からtenボタンを切り替えられます。" : "クイック設定の編集からも追加できます。"));
        } else toast("画面上端を2回下へスワイプし、クイック設定の編集から「ten ボタン」を追加してください。");
    }

    private TextView text(LinearLayout parent, String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(51, 72, 62)); view.setPadding(0, dp(8), 0, dp(12));
        parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false); view.setMinHeight(dp(52));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
        view.setOnClickListener(ignored -> action.run()); return view;
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
