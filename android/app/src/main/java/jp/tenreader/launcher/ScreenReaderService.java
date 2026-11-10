package jp.tenreader.launcher;

import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

public final class ScreenReaderService extends Service {
    static final String START="jp.tenreader.launcher.START_CAPTURE",READY="jp.tenreader.launcher.CAPTURE_READY",STOP="jp.tenreader.launcher.STOP_READER",GRANT="consent";
    private static final String CHANNEL="ten-screen-reader";
    static FrameSource.Factory frameFactory=ProjectionFrameSource::new;
    static OcrEngine.Factory ocrFactory=JapaneseOcrEngine::new;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Handler completions=new Handler(Looper.getMainLooper());
    private long ticket;
    private boolean live,started,watching,opsWatching;
    private Intent consent;
    private FrameSource frames;
    private ReaderOverlay overlay;
    private OcrJob job;
    private AppOpsManager appOps;
    private final Runnable timeout=()->fail("読み取りに時間がかかっています。閉じて再試行してください。");
    private final BroadcastReceiver screenOff=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){stopSelf();}
    };
    private final AppOpsManager.OnOpChangedListener permission=(op,pkg)->{
        if(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW.equals(op)&&getPackageName().equals(pkg))handler.post(()->{if(!Settings.canDrawOverlays(this))stopSelf();});
    };
    private static final class OcrJob {
        final long ticket;
        final Bitmap image;
        final OcrEngine engine;
        boolean released;
        OcrJob(long ticket,Bitmap image,OcrEngine engine){this.ticket=ticket;this.image=image;this.engine=engine;}
        void release(){if(released)return;released=true;try{engine.close();}finally{if(!image.isRecycled())image.recycle();}}
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        long incoming=intent.getLongExtra(CaptureActivity.TICKET,0);
        if(READY.equals(intent.getAction())) {
            if(live && incoming==ticket && CaptureTicket.owns(ticket) && !started)beginCapture();
            else if(!live){CaptureTicket.end(incoming);OverlayService.refreshVisibility();stopSelf();}
            return START_NOT_STICKY;
        }
        if(!START.equals(intent.getAction()) || !CaptureTicket.owns(incoming)) {if(!live)stopSelf();return START_NOT_STICKY;}
        if(live) {stopSelf();return START_NOT_STICKY;}
        ticket=incoming;
        consent=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra(GRANT,Intent.class):legacyGrant(intent);
        if(consent==null || !Settings.canDrawOverlays(this) || getSystemService(KeyguardManager.class).isKeyguardLocked()){stopSelf();return START_NOT_STICKY;}
        try {
            live=true;foreground(true);
            appOps=getSystemService(AppOpsManager.class);
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,getPackageName(),permission);
            opsWatching=true;
            if(Build.VERSION.SDK_INT>=33)registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenOff,new IntentFilter(Intent.ACTION_SCREEN_OFF));
            watching=true;
            handler.postDelayed(timeout,10000);
        } catch(RuntimeException e){stopSelf();}
        return START_NOT_STICKY;
    }
    @SuppressWarnings("deprecation") private static Intent legacyGrant(Intent intent){return intent.getParcelableExtra(GRANT);}
    private void beginCapture() {
        started=true;
        // Activity destruction and a short compositor settle period precede allocation of a fresh ImageReader.
        handler.postDelayed(()->{
            if(!valid())return;
            try {
                frames=frameFactory.create(this,handler);
                Intent grant=consent;consent=null;
                frames.start(grant,new FrameSource.Callback(){
                    @Override public void frame(Bitmap bitmap){acceptFrame(bitmap);}
                    @Override public void error(){fail("画面を取得できませんでした。許可や保護画面かどうかを確認してください。");}
                    @Override public void stopped(){if(live)stopSelf();}
                });
            } catch(RuntimeException e){fail("画面取得を開始できませんでした。もう一度許可して再試行してください。");}
        },300);
    }
    private boolean valid(){return live&&CaptureTicket.owns(ticket)&&Settings.canDrawOverlays(this)&&!getSystemService(KeyguardManager.class).isKeyguardLocked();}
    private void closeFrames(){if(frames!=null){FrameSource old=frames;frames=null;old.close();}consent=null;}
    private void acceptFrame(Bitmap bitmap) {
        if(!valid() || job!=null){bitmap.recycle();return;}
        handler.removeCallbacks(timeout);closeFrames();
        try {
            foreground(false);
            overlay=new ReaderOverlay(this,this::stopSelf,()->CaptureActivity.open(this));
            OcrJob current=new OcrJob(ticket,bitmap,ocrFactory.create());job=current;
            handler.postDelayed(timeout,30000);
            current.engine.read(bitmap,document->completions.post(()->complete(current,document)));
        } catch(RuntimeException|OutOfMemoryError e) {
            if(job!=null){job.release();job=null;}else bitmap.recycle();
            fail("文字の読み取りを開始できませんでした。閉じて再試行してください。");
        }
    }
    private void complete(OcrJob current,OcrDocument document) {
        try {
            if(!valid() || job!=current)return;
            handler.removeCallbacks(timeout);
            if(document==null || document.lines.isEmpty()){fail("文字を読み取れませんでした。保護画面には対応していません。画面を変えるか再試行してください。");return;}
            int width=Math.min(600,current.image.getWidth()),height=Math.max(1,Math.round(current.image.getHeight()*(width/(float)current.image.getWidth())));
            Bitmap preview=Bitmap.createScaledBitmap(current.image,width,height,true);
            // createScaledBitmap can return the source when its size is unchanged.
            if(preview==current.image)preview=current.image.copy(Bitmap.Config.ARGB_8888,false);
            overlay.showDocument(document,preview);
        } catch(RuntimeException|OutOfMemoryError e){fail("本文を表示できませんでした。閉じて再試行してください。");}
        finally {current.release();if(job==current)job=null;}
    }
    private void fail(String message) {
        if(!live || !CaptureTicket.owns(ticket))return;
        handler.removeCallbacks(timeout);closeFrames();job=null; // In-flight OCR retains its bitmap until its callback, but cannot display it.
        if(!valid()){stopSelf();return;}
        try {foreground(false);if(overlay==null)overlay=new ReaderOverlay(this,this::stopSelf,()->CaptureActivity.open(this));overlay.showError(message);}
        catch(RuntimeException e){stopSelf();}
    }
    private void foreground(boolean capturing) {
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"tenの画面読み取り",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,8,new Intent(this,ScreenReaderService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(capturing?"画面の文字を読み取り中":"tenで読書中")
            .setContentText("停止すると取得した画像と文章を破棄します")
            .addAction(new Notification.Action.Builder(null,"停止",stop).build()).setContentIntent(stop).setOngoing(true).setVisibility(Notification.VISIBILITY_SECRET).build();
        if(Build.VERSION.SDK_INT>=34)startForeground(8,notification,capturing?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else if(Build.VERSION.SDK_INT>=29)startForeground(8,notification,capturing?ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:0);
        else startForeground(8,notification);
    }
    @Override public void onConfigurationChanged(Configuration configuration){
        super.onConfigurationChanged(configuration);
        if(Build.VERSION.SDK_INT<34 && frames!=null)fail("画面の向きやサイズが変わりました。画面を読み取り直してください。");
        if(overlay!=null)overlay.layout();
    }
    @Override public void onDestroy() {
        live=false;handler.removeCallbacksAndMessages(null);closeFrames();
        // A running OCR callback owns its image. Do not recycle it underneath the recognizer.
        job=null;
        if(overlay!=null){overlay.close();overlay=null;}
        if(watching){unregisterReceiver(screenOff);watching=false;}
        if(opsWatching){appOps.stopWatchingMode(permission);opsWatching=false;}
        CaptureTicket.end(ticket);OverlayService.refreshVisibility();stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
