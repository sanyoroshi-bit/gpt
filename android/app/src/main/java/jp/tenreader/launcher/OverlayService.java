package jp.tenreader.launcher;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.ref.WeakReference;

public final class OverlayService extends Service {
    private static final String STOP = "jp.tenreader.launcher.STOP_OVERLAY";
    private static final String CHANNEL = "ten-launcher";
    static final String STATE_CHANGED = "jp.tenreader.launcher.STATE_CHANGED";
    private static volatile boolean running;
    private static WeakReference<OverlayService> instance=new WeakReference<>(null);
    private boolean screenAvailable=true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager windows;
    private TextView bubble;
    private WindowManager.LayoutParams params;
    private SharedPreferences position;
    private boolean receiverRegistered;
    private AppOpsManager appOps;
    private boolean watchingPermission;
    private final AppOpsManager.OnOpChangedListener permissionChanged = (op, packageName) -> {
        if (AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW.equals(op) && getPackageName().equals(packageName)) {
            handler.post(() -> { if (!Settings.canDrawOverlays(this)) stopSelf(); });
        }
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            screenAvailable=!Intent.ACTION_SCREEN_OFF.equals(intent.getAction());
            refreshVisibility();
        }
    };

    static boolean isRunning() { return running; }
    @Override public void onCreate(){super.onCreate();instance=new WeakReference<>(this);screenAvailable=getSystemService(android.os.PowerManager.class).isInteractive();}
    static void refreshVisibility(){
        OverlayService service=instance.get();
        if(service==null)return;
        Runnable update=()->{
            if(service.bubble!=null)service.bubble.setVisibility(CaptureTicket.busy()||!service.screenAvailable||service.getSystemService(KeyguardManager.class).isKeyguardLocked()?View.GONE:View.VISIBLE);
            service.sendBroadcast(new Intent(STATE_CHANGED).setPackage(service.getPackageName()));
        };
        if(Looper.myLooper()==Looper.getMainLooper())update.run();else service.handler.post(update);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent!=null&&STOP.equals(intent.getAction())) || !Settings.canDrawOverlays(this)) {
            stopSelf(); return START_NOT_STICKY;
        }
        if (bubble != null) return START_STICKY;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "tenの起動ボタン", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setAction(MainActivity.OPEN_READER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, OverlayService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("tenの起動ボタンを表示中")
                .setContentText("アイコンで画面の本文を読む · 長押しで非表示")
                .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(new Notification.Action.Builder(null, "非表示にする", stop).build()).build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, notification);
            addBubble();
            running = true;
            refreshVisibility();
            updateTile();
            sendBroadcast(new Intent(STATE_CHANGED).setPackage(getPackageName()));
        } catch (RuntimeException e) {
            Toast.makeText(this, "起動ボタンを表示できません。重ね表示の許可を確認してください。", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        // The OS may restore only this launcher after process reclamation. Capture never restarts.
        // Explicit stopSelf/stopService still ends the service; there is no boot receiver.
        return START_STICKY;
    }

    // Taps call performClick() and holds call performLongClick(); accessibility uses the same registered listeners.
    @SuppressLint("ClickableViewAccessibility")
    private void addBubble() {
        Context windowContext = this;
        if (Build.VERSION.SDK_INT >= 30) {
            Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) throw new IllegalStateException("No display for the launcher button");
            windowContext = createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        windows = windowContext.getSystemService(WindowManager.class);
        position = getSharedPreferences("bubble-position", MODE_PRIVATE);
        int size = Math.round(56 * getResources().getDisplayMetrics().density);
        params = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        BubbleBounds bounds = bounds();
        params.x = bounds.clampX(position.getInt("x", bounds.right));
        params.y = bounds.clampY(position.getInt("y", bounds.top + size * 2));
        bubble = new TextView(windowContext);
        bubble.setText(R.string.bubble_label); bubble.setTextSize(18); bubble.setGravity(Gravity.CENTER);
        bubble.setTypeface(Typeface.create("serif", Typeface.BOLD));
        bubble.setTextColor(Color.rgb(255, 252, 246));
        bubble.setContentDescription("画面の本文を読み取る。ドラッグで移動、長押しで非表示。");
        GradientDrawable background = new GradientDrawable(); background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.rgb(51, 72, 62)); background.setStroke(Math.max(1, size / 28), Color.rgb(242, 223, 159));
        bubble.setBackground(background);
        bubble.setOnClickListener(view -> {
            try { CaptureActivity.open(this); }
            catch (RuntimeException e) { Toast.makeText(this, "tenを開けません。アプリアイコンから開いてください。", Toast.LENGTH_LONG).show(); }
        });
        bubble.setOnLongClickListener(view -> { stopSelf(); return true; });
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean dragged, longPressed;
            final int slop = ViewConfiguration.get(bubble.getContext()).getScaledTouchSlop();
            final Runnable hold = () -> { longPressed = true; if (bubble != null) bubble.performLongClick(); };
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); downY = event.getRawY(); startX = params.x; startY = params.y;
                        dragged = false; longPressed = false;
                        handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                        if (!dragged && BubbleBounds.isDrag(dx, dy, slop)) { dragged = true; handler.removeCallbacks(hold); }
                        if (dragged && !longPressed) move(startX + Math.round(dx), startY + Math.round(dy));
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(hold);
                        if (dragged) savePosition();
                        else if (!longPressed) view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_POINTER_DOWN:
                        handler.removeCallbacks(hold); dragged = true; return true;
                    default: return true;
                }
            }
        });
        if (getSystemService(KeyguardManager.class).isKeyguardLocked()) bubble.setVisibility(View.GONE);
        windows.addView(bubble, params);
        OwnOverlayWindows.add(bubble);
        IntentFilter filter = new IntentFilter(); filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, filter);
        receiverRegistered = true;
        appOps = getSystemService(AppOpsManager.class);
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, getPackageName(), permissionChanged);
        watchingPermission = true;
        if (!Settings.canDrawOverlays(this)) throw new SecurityException("Overlay permission was revoked");
    }

    private BubbleBounds bounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = windows.getCurrentWindowMetrics();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return new BubbleBounds(metrics.getBounds().width(), metrics.getBounds().height(), insets.left, insets.top, insets.right, insets.bottom, params.width);
        }
        DisplayMetrics metrics = new DisplayMetrics(); windows.getDefaultDisplay().getRealMetrics(metrics);
        // Older releases expose no reliable service WindowInsets; keep clear of system bars.
        int inset = Math.round(32 * metrics.density);
        return new BubbleBounds(metrics.widthPixels, metrics.heightPixels, inset, inset, inset, inset * 2, params.width);
    }

    private void move(int x, int y) {
        if (bubble == null || !bubble.isAttachedToWindow()) return;
        BubbleBounds bounds = bounds(); params.x = bounds.clampX(x); params.y = bounds.clampY(y);
        try { windows.updateViewLayout(bubble, params); }
        catch (RuntimeException e) { stopSelf(); }
    }
    private void savePosition() { if (position != null && params != null) position.edit().putInt("x", params.x).putInt("y", params.y).apply(); }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (params != null) { move(params.x, params.y); savePosition(); }
    }
    private void updateTile() { TileService.requestListeningState(this, new ComponentName(this, ReaderTileService.class)); }
    @Override public void onDestroy() {
        running = false;
        if(instance.get()==this)instance.clear();
        CaptureTicket.cancelAll();stopService(new Intent(this,ScreenReaderService.class));
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false; }
        if (watchingPermission) { appOps.stopWatchingMode(permissionChanged); watchingPermission = false; }
        savePosition();
        OwnOverlayWindows.remove(bubble);
        if (bubble != null && bubble.isAttachedToWindow()) windows.removeView(bubble);
        bubble = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        updateTile();
        sendBroadcast(new Intent(STATE_CHANGED).setPackage(getPackageName()));
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
