package jp.tenreader.launcher;

import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowAccessibilityRecord;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36,qualifiers="w320dp-h720dp-mdpi",shadows={AutoScrollServiceTest.FreshWindows.class,AutoScrollServiceTest.FreshNodeWindows.class})
public class AutoScrollServiceTest {
    // Android returns window snapshots owned by each caller. Robolectric's stock setters retain
    // the fixture object itself, so return copies instead of reusing a recycled fixture.
    @Implements(android.accessibilityservice.AccessibilityService.class)
    public static class FreshWindows extends org.robolectric.shadows.ShadowAccessibilityService {
        @Implementation protected List<AccessibilityWindowInfo> getWindows(){List<AccessibilityWindowInfo> copies=new ArrayList<>();for(AccessibilityWindowInfo window:super.getWindows())copies.add(AccessibilityWindowInfo.obtain(window));return copies;}
    }
    @Implements(AccessibilityNodeInfo.class)
    public static class FreshNodeWindows extends org.robolectric.shadows.ShadowAccessibilityNodeInfo {
        @Implementation protected AccessibilityWindowInfo getWindow(){AccessibilityWindowInfo window=super.getWindow();return window==null?null:AccessibilityWindowInfo.obtain(window);}
    }
    ServiceController<AutoScrollService> controller;AutoScrollService service;AccessibilityNodeInfo root;AccessibilityWindowInfo window;ScrollPort.Target target;long ticket;boolean cancelled;
    @Before public void setup(){
        CaptureTicket.cancelAll();ShadowSettings.setCanDrawOverlays(true);controller=Robolectric.buildService(AutoScrollService.class).create();service=controller.get();service.onServiceConnected();
        AutoScrollService.knownOverlay=id->id==10;AutoScrollService.controlsHidden=()->true;
        window=AccessibilityWindowInfo.obtain();shadowOf(window).setId(7);shadowOf(window).setType(AccessibilityWindowInfo.TYPE_APPLICATION);shadowOf(window).setBoundsInScreen(new Rect(0,0,320,720));shadowOf(window).setActive(true);shadowOf(window).setFocused(true);shadowOf(window).setLayer(1);
        root=node(new Rect(0,0,320,720),true);shadowOf(window).setRoot(root);shadowOf(service).setRootInActiveWindow(root);
        shadowOf(window).setDisplayId(0);shadowOf(service).setWindows(List.of(window));
        target=service.snapshot();assertNotNull(target);ticket=CaptureTicket.begin(target);assertTrue(service.begin(ticket,target,()->cancelled=true));
    }
    @After public void cleanup(){controller.destroy();CaptureTicket.cancelAll();AutoScrollService.knownOverlay=OwnOverlayWindows::contains;AutoScrollService.controlsHidden=OwnOverlayWindows::hidden;}
    private AccessibilityNodeInfo node(Rect bounds,boolean scrollable){
        AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain();n.setPackageName("example.reader");n.setVisibleToUser(true);n.setScrollable(scrollable);n.setBoundsInScreen(bounds);shadowOf(n).setAccessibilityWindowInfo(window);
        if(scrollable){n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN);shadowOf(n).setOnPerformActionListener((action,args)->true);}return n;
    }
    private void event(int type,AccessibilityNodeInfo source){
        AccessibilityEvent event=AccessibilityEvent.obtain(type);event.setPackageName("example.reader");event.setEventTime(SystemClock.uptimeMillis());
        ShadowAccessibilityRecord record=Shadow.extract(event);record.setWindowId(7);record.setSourceNode(source);service.onAccessibilityEvent(event);event.recycle();
    }
    @Test public void oneActionNeedsMatchingEventBeforeSuccess(){
        AccessibilityNodeInfo small=node(new Rect(0,0,320,70),true);shadowOf(root).addChild(small);List<Boolean> results=new ArrayList<>();
        assertTrue(service.forward(ticket,target,results::add));assertEquals(List.of(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()),shadowOf(root).getPerformedActions());assertTrue(shadowOf(small).getPerformedActions().isEmpty());
        assertFalse(service.forward(ticket,target,results::add));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));assertTrue(results.isEmpty());
        event(AccessibilityEvent.TYPE_VIEW_SCROLLED,root);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(251));assertEquals(List.of(true),results);
        assertTrue(shadowOf(service).getGlobalActionsPerformed().isEmpty());assertTrue(shadowOf(service).getGesturesDispatched().isEmpty());
    }
    @Test public void actionTrueWithoutEventIsNotSuccess(){List<Boolean> results=new ArrayList<>();assertTrue(service.forward(ticket,target,results::add));shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1401));assertEquals(List.of(false),results);}
    @Test public void manualClickCancelsPendingScroll(){List<Boolean> results=new ArrayList<>();assertTrue(service.forward(ticket,target,results::add));event(AccessibilityEvent.TYPE_VIEW_CLICKED,root);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertTrue(cancelled);assertTrue(results.isEmpty());assertFalse(service.forward(ticket,target,results::add));}
    @Test public void scrollFromWrongRegionCancels(){assertTrue(service.forward(ticket,target,result->{}));event(AccessibilityEvent.TYPE_VIEW_SCROLLED,node(new Rect(0,100,320,300),true));assertTrue(cancelled);}
    @Test public void changingForegroundWindowCancelsWithoutMoreActions(){root.setPackageName("other.application");event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,root);shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(151));assertTrue(cancelled);assertFalse(service.forward(ticket,target,result->{}));assertTrue(shadowOf(root).getPerformedActions().isEmpty());}
    @Test public void nodesOutsideTargetWindowCannotBeScrolled(){root.setScrollable(false);AccessibilityNodeInfo outside=node(new Rect(0,0,640,720),true);shadowOf(root).addChild(outside);assertFalse(service.forward(ticket,target,result->{}));assertTrue(shadowOf(outside).getPerformedActions().isEmpty());}
    @Test public void noOwnedCaptureMeansNoAutomaticAction(){CaptureTicket.cancelAll();assertFalse(service.forward(ticket,target,result->{}));assertTrue(shadowOf(root).getPerformedActions().isEmpty());}
    @Test public void missingActualWindowFailsClosed(){shadowOf(root).setAccessibilityWindowInfo(null);assertNull(service.snapshot());assertFalse(service.forward(ticket,target,result->{}));assertTrue(shadowOf(root).getPerformedActions().isEmpty());}
    @Test public void missingWindowInventoryFailsClosed(){shadowOf(service).setWindows(List.of());assertNull(service.snapshot());assertFalse(service.captureSafe(target));}
    private AccessibilityWindowInfo upperWindow(int id,String pkg,int type,Rect bounds){
        AccessibilityWindowInfo upper=AccessibilityWindowInfo.obtain();shadowOf(upper).setId(id);shadowOf(upper).setType(type);shadowOf(upper).setBoundsInScreen(bounds);shadowOf(upper).setLayer(2);
        AccessibilityNodeInfo overlay=AccessibilityNodeInfo.obtain();overlay.setPackageName(pkg);overlay.setVisibleToUser(true);shadowOf(overlay).setAccessibilityWindowInfo(upper);shadowOf(upper).setRoot(overlay);
        shadowOf(upper).setDisplayId(0);shadowOf(service).setWindows(List.of(upper,window));return upper;
    }
    @Test public void overlappingForeignWindowBlocksBothCaptureAndAction(){upperWindow(11,"other.overlay",AccessibilityWindowInfo.TYPE_SYSTEM,new Rect(0,60,320,140));assertNull(service.snapshot());assertFalse(service.captureSafe(target));assertFalse(service.forward(ticket,target,result->{}));assertTrue(shadowOf(root).getPerformedActions().isEmpty());}
    @Test public void knownReaderControlsCanBeActiveButMustBeHiddenForCapture(){
        AccessibilityWindowInfo reader=upperWindow(10,service.getPackageName(),AccessibilityWindowInfo.TYPE_SYSTEM,new Rect(10,50,310,400));shadowOf(service).setRootInActiveWindow(reader.getRoot());
        assertTrue(service.matches(target));AutoScrollService.controlsHidden=()->false;assertFalse(service.captureSafe(target));assertFalse(service.forward(ticket,target,result->{}));
        AutoScrollService.controlsHidden=()->true;assertTrue(service.captureSafe(target));assertTrue(service.forward(ticket,target,result->{}));
    }
    @Test public void ownOrdinaryActivityIsNotAReaderException(){
        AccessibilityWindowInfo activity=upperWindow(12,service.getPackageName(),AccessibilityWindowInfo.TYPE_APPLICATION,new Rect(0,0,320,720));shadowOf(activity).setActive(true);shadowOf(activity).setFocused(true);shadowOf(service).setRootInActiveWindow(activity.getRoot());
        assertNull(service.snapshot());event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,activity.getRoot());shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(151));assertTrue(cancelled);
    }
    @Test public void granularScrollRequestsSeventyPercentOnce(){
        final android.os.Bundle[] supplied={null};root.setGranularScrollingSupported(true);shadowOf(root).setOnPerformActionListener((action,args)->{supplied[0]=args;return true;});
        assertTrue(service.forward(ticket,target,result->{}));assertNotNull(supplied[0]);assertEquals(.7F,supplied[0].getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT),.001F);assertEquals(1,shadowOf(root).getPerformedActions().size());
    }
    @Test public void registryRecognizesOnlyAttachedOwnedViews(){
        AccessibilityWindowInfo reader=upperWindow(10,service.getPackageName(),AccessibilityWindowInfo.TYPE_SYSTEM,new Rect(10,50,310,400));
        android.content.Context visual=OverlayWindows.context(service);
        android.view.View view=new android.view.View(visual){@Override public AccessibilityNodeInfo createAccessibilityNodeInfo(){AccessibilityNodeInfo info=AccessibilityNodeInfo.obtain();shadowOf(info).setAccessibilityWindowInfo(reader);return info;}};
        android.view.WindowManager manager=visual.getSystemService(android.view.WindowManager.class);
        manager.addView(view,new android.view.WindowManager.LayoutParams(100,100,android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,android.graphics.PixelFormat.TRANSLUCENT));
        shadowOf(Looper.getMainLooper()).idle();
        try{
            assertTrue("fixture must be attached",view.isAttachedToWindow());
            assertFalse("unregistered view",OwnOverlayWindows.contains(10));OwnOverlayWindows.add(view);
            assertTrue("registered attached view",OwnOverlayWindows.contains(10));assertFalse("visible controls block capture",OwnOverlayWindows.hidden());
            view.setVisibility(android.view.View.GONE);assertTrue("hidden controls permit capture",OwnOverlayWindows.hidden());
        }
        finally{OwnOverlayWindows.remove(view);manager.removeViewImmediate(view);}
        assertFalse(OwnOverlayWindows.contains(10));
    }
}
