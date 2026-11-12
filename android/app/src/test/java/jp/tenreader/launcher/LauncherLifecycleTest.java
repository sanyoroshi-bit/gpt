package jp.tenreader.launcher;

import android.content.Intent;
import android.app.AppOpsManager;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSettings;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class LauncherLifecycleTest {
    @Test public void existingControlsHandleOpenFromBubbleOrNotification() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            controller.newIntent(new Intent(activity, MainActivity.class).setAction(MainActivity.OPEN_READER));
            Intent browser = shadowOf(activity).getNextStartedActivity();
            assertNotNull(browser);
            assertEquals(Intent.ACTION_VIEW, browser.getAction());
            assertEquals("https://gpt-delta.vercel.app/", browser.getDataString());
            assertTrue(activity.isFinishing());
        }
    }

    @Test public void browserActionIsConsumedBeforeRotation() {
        Intent intent = new Intent().setAction(MainActivity.OPEN_READER);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, intent).create()) {
            MainActivity first = controller.get();
            assertNotNull(shadowOf(first).getNextStartedActivity());
            assertNull(first.getIntent().getAction());
            Bundle state = new Bundle(); controller.saveInstanceState(state);
            try (ActivityController<MainActivity> recreated = Robolectric.buildActivity(MainActivity.class, first.getIntent()).create(state)) {
                assertNull(shadowOf(recreated.get()).getNextStartedActivity());
            }
        }
    }

    @Test public void publicEntryDoesNotForwardInternalActionOrUntrustedData() {
        Intent external = new Intent().setAction(MainActivity.TOGGLE_OVERLAY).setData(android.net.Uri.parse("https://outside.example/"));
        try (ActivityController<EntryActivity> controller = Robolectric.buildActivity(EntryActivity.class, external).create()) {
            Intent controls = shadowOf(controller.get()).getNextStartedActivity();
            assertEquals(MainActivity.class.getName(), controls.getComponent().getClassName());
            assertNull(controls.getAction()); assertNull(controls.getData());
        }
    }

    @Test public void existingControlsHandleTileAction() {
        ShadowSettings.setCanDrawOverlays(true);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            controller.newIntent(new Intent(activity, MainActivity.class).setAction(MainActivity.TOGGLE_OVERLAY));
            Intent service = shadowOf(activity).getNextStartedService();
            assertNotNull(service);
            assertEquals(OverlayService.class.getName(), service.getComponent().getClassName());
            assertNull(activity.getIntent().getAction());
        }
    }

    @Test public void revokedOverlayPermissionStopsWithoutAddingWindow() {
        ShadowSettings.setCanDrawOverlays(false);
        ServiceController<OverlayService> controller = Robolectric.buildService(OverlayService.class).create();
        try {
            OverlayService service = controller.get();
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(new Intent(service, OverlayService.class), 0, 1));
            assertTrue(shadowOf(service).isStoppedBySelf());
            assertFalse(OverlayService.isRunning());
        } finally { controller.destroy(); }
    }

    @Test public void returningFromPermissionSettingsStartsOnlyAfterGrantAndOnlyOnce() {
        ShadowSettings.setCanDrawOverlays(false);
        Bundle pending = new Bundle(); pending.putBoolean("waitingForOverlay", true);
        try (ActivityController<MainActivity> denied = Robolectric.buildActivity(MainActivity.class).create(pending).start().resume()) {
            assertNull(shadowOf(denied.get()).getNextStartedService());
        }
        ShadowSettings.setCanDrawOverlays(true);
        try (ActivityController<MainActivity> granted = Robolectric.buildActivity(MainActivity.class).create(pending).start().resume()) {
            assertNotNull(shadowOf(granted.get()).getNextStartedService());
            granted.pause().resume();
            assertNull(shadowOf(granted.get()).getNextStartedService());
        }
    }

    @Test public void stoppingServiceRefreshesVisibleControls() {
        ShadowSettings.setCanDrawOverlays(true);
        try (ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup()) {
            ServiceController<OverlayService> service = Robolectric.buildService(OverlayService.class).create();
            try {
                OverlayService overlay = service.get();
                overlay.onStartCommand(new Intent(overlay, OverlayService.class), 0, 1);
                shadowOf(Looper.getMainLooper()).idle();
                assertTrue(OverlayService.isRunning());
                assertTrue(hasText(activity.get().getWindow().getDecorView(), "起動ボタンを表示中です。"));
            } finally { service.destroy(); }
            shadowOf(Looper.getMainLooper()).idle();
            assertFalse(OverlayService.isRunning());
            assertTrue(hasText(activity.get().getWindow().getDecorView(), "起動ボタンは非表示です。"));
        }
    }

    private static boolean hasText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i), text)) return true;
        }
        return false;
    }

    @Test public void revokingPermissionWhileVisibleStopsService() {
        ShadowSettings.setCanDrawOverlays(true);
        ServiceController<OverlayService> controller = Robolectric.buildService(OverlayService.class).create();
        try {
            OverlayService service = controller.get();
            service.onStartCommand(new Intent(service, OverlayService.class), 0, 1);
            assertTrue(OverlayService.isRunning());
            ShadowSettings.setCanDrawOverlays(false);
            AppOpsManager ops = service.getSystemService(AppOpsManager.class);
            shadowOf(ops).setMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, android.os.Process.myUid(), service.getPackageName(), AppOpsManager.MODE_ERRORED);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(shadowOf(service).isStoppedBySelf());
        } finally { controller.destroy(); }
        assertFalse(OverlayService.isRunning());
    }
    @Test public void closingSettingsAndRemovingItsTaskKeepsTheLauncher(){
        ShadowSettings.setCanDrawOverlays(true);ServiceController<OverlayService> service=Robolectric.buildService(OverlayService.class).create();
        try{
            assertEquals(android.app.Service.START_STICKY,service.get().onStartCommand(new Intent(),0,1));
            try(ActivityController<MainActivity> activity=Robolectric.buildActivity(MainActivity.class).setup()){activity.get().finish();}
            service.get().onTaskRemoved(new Intent());assertTrue(OverlayService.isRunning());assertFalse(shadowOf(service.get()).isStoppedBySelf());
        }finally{service.destroy();}
    }
    @Test public void osRestartRestoresOnlyTheLauncherWithoutCapture(){
        ShadowSettings.setCanDrawOverlays(true);CaptureTicket.cancelAll();ServiceController<OverlayService> service=Robolectric.buildService(OverlayService.class).create();
        try{assertEquals(android.app.Service.START_STICKY,service.get().onStartCommand(null,0,1));assertTrue(OverlayService.isRunning());assertFalse(CaptureTicket.busy());assertNull(shadowOf(service.get()).getNextStartedActivity());}
        finally{service.destroy();}
    }
    @Test public void screenOnWithoutUnlockBroadcastRestoresTheIcon(){
        ShadowSettings.setCanDrawOverlays(true);ServiceController<OverlayService> service=Robolectric.buildService(OverlayService.class).create();
        try{
            service.get().onStartCommand(new Intent(),0,1);shadowOf(Looper.getMainLooper()).idle();
            View bubble=org.robolectric.util.ReflectionHelpers.getField(service.get(),"bubble");
            service.get().sendBroadcast(new Intent(Intent.ACTION_SCREEN_OFF));shadowOf(Looper.getMainLooper()).idle();assertEquals(View.GONE,bubble.getVisibility());assertTrue(OverlayService.isRunning());
            service.get().sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));shadowOf(Looper.getMainLooper()).idle();assertEquals(View.VISIBLE,bubble.getVisibility());assertTrue(OverlayService.isRunning());assertFalse(shadowOf(service.get()).isStoppedBySelf());
        }finally{service.destroy();}
    }
    @Test public void restartWhileScreenOffStaysHiddenUntilScreenOnAndUnlock(){
        ShadowSettings.setCanDrawOverlays(true);
        android.app.Application app=org.robolectric.RuntimeEnvironment.getApplication();
        shadowOf(app.getSystemService(android.os.PowerManager.class)).setIsInteractive(false);shadowOf(app.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(true);
        ServiceController<OverlayService> service=Robolectric.buildService(OverlayService.class).create();
        try{
            service.get().onStartCommand(null,0,1);View bubble=org.robolectric.util.ReflectionHelpers.getField(service.get(),"bubble");assertEquals(View.GONE,bubble.getVisibility());
            service.get().sendBroadcast(new Intent(Intent.ACTION_SCREEN_ON));shadowOf(Looper.getMainLooper()).idle();assertEquals(View.GONE,bubble.getVisibility());
            shadowOf(app.getSystemService(android.app.KeyguardManager.class)).setKeyguardLocked(false);service.get().sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));shadowOf(Looper.getMainLooper()).idle();assertEquals(View.VISIBLE,bubble.getVisibility());
        }finally{service.destroy();}
    }
    @Test public void explicitHideIsNotSticky(){
        ShadowSettings.setCanDrawOverlays(true);ServiceController<OverlayService> service=Robolectric.buildService(OverlayService.class).create();
        try{service.get().onStartCommand(new Intent(),0,1);assertEquals(android.app.Service.START_NOT_STICKY,service.get().onStartCommand(new Intent().setAction("jp.tenreader.launcher.STOP_OVERLAY"),0,2));assertTrue(shadowOf(service.get()).isStoppedBySelf());}
        finally{service.destroy();}
    }
}
