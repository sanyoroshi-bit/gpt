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
    private TextView autoStatus;
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
        text(content, "試用版 0.4.0 · Android 8以降", 13);
        button(content, "ブラウザ版tenを開く ↗", this::openReader);
        status = text(content, "", 15);
        enable = button(content, "画面のボタンを表示", this::enableOverlay);
        button(content, "ボタンを非表示にする", () -> { stopService(new Intent(this, OverlayService.class)); refresh(); });
        text(content, "読みたいアプリを開いて、小さなtenを押してください。画面取得を許可すると、日本語の本文候補を読み取り、そのアプリ上で定点表示します。範囲・文章はリーダー内で修正できます。ドラッグでアイコン移動、長押しで非表示です。", 15);
        autoStatus=text(content,"",14);
        Button automatic=button(content,"自動スクロールを設定",this::configureAutomatic);automatic.setId(R.id.auto_scroll_setting);
        text(content,"任意の設定です。同じ記事の下方向への移動を繰り返して本文を読み、縦移動の末尾で停止します。横の別記事へは進みません。続きの一致を確認できない場合も停止します。画面共有は終了まで継続し、アプリ切替・検知した手動操作で終了します。1回の読書は最大50画面です。",14);
        text(content,"タイトルや表を本文から自動で除外します。誤判定は範囲・文章の欄で確認し、全文に戻せます。固有名詞は端末内の日本語辞書で区切り、未知の名称は区切り辞書に登録できます。",14);
        button(content,"日本語辞書のライセンス",this::showDictionaryLicenses);
        button(content,"OS設定で有効化できない場合の案内",()->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://support.google.com/android/answer/12623953?hl=ja")));}catch(ActivityNotFoundException e){toast("Android公式ヘルプの『制限付き設定』を確認してください。");}
        });
        button(content, "クイック設定にボタンを追加", this::addTile);
        text(content, "クイック設定の「ten ボタン」からも表示・非表示を切り替えられます。追加されないときは、画面上端を2回下へスワイプし、編集から追加してください。", 14);
        button(content, "通知の設定", () -> {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            try { startActivity(intent); } catch (ActivityNotFoundException e) { toast("端末の設定からtenの通知を確認してください。"); }
        });
        text(content, "表示には重ね表示の許可、読み取りにはOSの画面取得への同意を使います。取得した画像と本文は端末内で処理し、閉じると破棄します。保護画面には対応していません。画面外の続きは、画面を動かして読み取り直せます。", 14);
        text(content, "設定画面や読書画面を閉じても、tenアイコンは残ります。消すときは長押しか「ボタンを非表示にする」を使ってください。OSや省電力設定で停止する場合があります。端末の再起動後はこの画面から表示してください。", 14);
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
            .setMessage("次の設定画面で、tenに「ほかのアプリの上に重ねて表示」を許可してください。許可して戻るとボタンを表示します。この許可はボタン表示用です。読み取りはアイコンを押し、OSの画面取得に同意した後に開始します。")
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
        status.setText(CaptureTicket.busy()?"画面の読み取り・読書中です。":running ? "起動ボタンを表示中です。" : "起動ボタンは非表示です。");
        enable.setEnabled(!running);
        if(autoStatus!=null){boolean requested=getSharedPreferences("native-reader",0).getBoolean("auto-scroll",false);autoStatus.setText(!requested?"自動スクロール：OFF":AutoScrollService.port()==null?"自動スクロール：ON（OS設定の有効化待ち）":"自動スクロール：ON");}
    }

    private void configureAutomatic(){
        if(getSharedPreferences("native-reader",0).getBoolean("auto-scroll",false)){
            new AlertDialog.Builder(this).setTitle("自動スクロールの設定")
                .setMessage("OFFにすると現在の自動読書も終了します。OS側の権限はAndroid設定からいつでも解除できます。")
                .setNegativeButton("閉じる",null).setNeutralButton("OS設定を開く",(d,w)->accessibilitySettings())
                .setPositiveButton("OFFにする",(d,w)->{getSharedPreferences("native-reader",0).edit().putBoolean("auto-scroll",false).apply();CaptureTicket.cancelAll();stopService(new Intent(this,ScreenReaderService.class));OverlayService.refreshVisibility();refresh();}).show();return;
        }
        new AlertDialog.Builder(this).setTitle("自動スクロールを使う")
            .setMessage("読みたいアプリの画面構造・対象情報にアクセスし、スクロール可能な領域を判定します。ユーザー補助機能の文章データは参照・保存・送信せず、本文は画面の画像から端末内で文字認識します。\n\n同意した読書中だけ、読了ごとに対象へ1回スクロールします。対象切替・検知した手動操作・画面ロックで停止します。読書欄の『閉じる』、通知の停止、OS設定でも止められます。\n\n自動時は画面全体の取得をOSへ要求し、対象アプリ領域だけを文字認識へ渡します。次のOS設定で『ten 自動スクロール』を手動で有効にしてください。")
            .setNegativeButton("使わない",null)
            .setPositiveButton("同意してOS設定へ",(d,w)->{getSharedPreferences("native-reader",0).edit().putBoolean("auto-scroll",true).apply();accessibilitySettings();refresh();}).show();
    }
    private void accessibilitySettings(){try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(ActivityNotFoundException e){toast("端末のユーザー補助設定からtenを有効にしてください。");}}
    private void showDictionaryLicenses(){
        StringBuilder text=new StringBuilder();
        try{for(String file:new String[]{"kuromoji-LICENSE.txt","kuromoji-NOTICE.txt","ipadic-NOTICE.txt","kuromoji-CONTRIBUTORS.txt"}){
            text.append(file).append("\n\n");try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(getAssets().open("licenses/"+file),java.nio.charset.StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)text.append(line).append('\n');}text.append("\n\n");
        }}catch(java.io.IOException e){toast("ライセンスを開けませんでした。");return;}
        new AlertDialog.Builder(this).setTitle("Kuromoji / IPADIC").setMessage(text.toString()).setPositiveButton("閉じる",null).show();
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
