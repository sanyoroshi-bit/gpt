package jp.tenreader.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/** Enabled only through Android Settings. No text, gestures, clicks, keys or global actions. */
public final class AutoScrollService extends AccessibilityService implements ScrollPort {
    private static WeakReference<AutoScrollService> current=new WeakReference<>(null);
    static IntPredicate knownOverlay=OwnOverlayWindows::contains;
    static BooleanSupplier controlsHidden=OwnOverlayWindows::hidden;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long owner,pendingSince;
    private Target target;
    private Runnable interrupted;
    private Consumer<Boolean> pending;
    private Rect pendingBounds;
    private Rect verticalBounds;
    private String verticalClass;
    private boolean sawScroll;
    private final Runnable settled=()->finishScroll(sawScroll);
    private final Runnable deadline=()->finishScroll(false);
    static ScrollPort port(){return current.get();}
    @Override protected void onServiceConnected(){super.onServiceConnected();current=new WeakReference<>(this);}
    private static final class Resolved implements AutoCloseable {
        final Target target;final AccessibilityNodeInfo root;
        Resolved(Target target,AccessibilityNodeInfo root){this.target=target;this.root=root;}
        @Override public void close(){root.recycle();}
    }
    private boolean allowedPackage(String pkg){
        if(pkg.equals(getPackageName())||pkg.equals("android")||pkg.startsWith("com.android.systemui")||pkg.contains("permissioncontroller"))return false;
        android.content.pm.ResolveInfo home=getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0);
        return home==null||home.activityInfo==null||!pkg.equals(home.activityInfo.packageName);
    }
    /** The active root, window inventory, z-order and geometry must agree. No fallback root bounds. */
    private Resolved resolve(boolean capturing){
        AccessibilityNodeInfo active=null,root=null;AccessibilityWindowInfo activeInfo=null,rootInfo=null;List<AccessibilityWindowInfo> windows=null;
        try{
            if(capturing&&!controlsHidden.getAsBoolean())return null;
            active=getRootInActiveWindow();if(active==null||active.getWindowId()<0)return null;
            activeInfo=active.getWindow();if(activeInfo==null)return null;
            windows=getWindows();if(windows==null||windows.isEmpty())return null;
            AccessibilityWindowInfo activeWindow=null,candidate=null;
            for(AccessibilityWindowInfo window:windows)if(window.getId()==active.getWindowId())activeWindow=window;
            if(activeWindow==null||activeInfo.getId()!=activeWindow.getId()||activeInfo.getType()!=activeWindow.getType())return null;
            if(knownOverlay.test(activeWindow.getId())&&getPackageName().contentEquals(active.getPackageName()==null?"":active.getPackageName())){
                // Our non-focusable controls can become the active accessibility window on touch.
                for(AccessibilityWindowInfo window:windows){
                    if(window.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&window.isFocused()){
                        if(candidate!=null)return null;candidate=window;
                    }
                }
                if(candidate==null)return null;
            }else candidate=activeWindow;
            if(candidate.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||(!candidate.isActive()&&!candidate.isFocused())||candidate.getId()<0)return null;
            if(Build.VERSION.SDK_INT>=30&&candidate.getDisplayId()!=Display.DEFAULT_DISPLAY)return null;
            root=candidate==activeWindow?active:candidate.getRoot();
            if(root==active)active=null;
            if(root==null||root.getPackageName()==null||!root.isVisibleToUser()||root.getWindowId()!=candidate.getId())return null;
            rootInfo=root.getWindow();if(rootInfo==null||rootInfo.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||rootInfo.getId()!=candidate.getId())return null;
            String pkg=root.getPackageName().toString();if(!allowedPackage(pkg))return null;
            Context visual=OverlayWindows.context(this);Rect screen=OverlayWindows.fullBounds(visual),appBounds=new Rect();candidate.getBoundsInScreen(appBounds);
            if(appBounds.isEmpty()||!new Rect(0,0,screen.width(),screen.height()).contains(appBounds))return null;
            Rect crop=new Rect(appBounds);if(!crop.intersect(OverlayWindows.safeBounds(visual))||crop.width()<100||crop.height()<100)return null;
            for(AccessibilityWindowInfo window:windows){
                if(window.getId()==candidate.getId()||window.getLayer()<candidate.getLayer())continue;
                if(Build.VERSION.SDK_INT>=30&&window.getDisplayId()!=Display.DEFAULT_DISPLAY)continue;
                Rect other=new Rect();window.getBoundsInScreen(other);
                if(Rect.intersects(crop,other)&&!knownOverlay.test(window.getId()))return null;
            }
            Resolved resolved=new Resolved(new Target(pkg,candidate.getId(),crop,appBounds,screen.width(),screen.height()),root);root=null;return resolved;
        }catch(RuntimeException e){return null;}
        finally{
            if(active!=null)active.recycle();if(root!=null)root.recycle();
            java.util.Set<AccessibilityWindowInfo> recycled=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            if(activeInfo!=null&&recycled.add(activeInfo))activeInfo.recycle();if(rootInfo!=null&&recycled.add(rootInfo))rootInfo.recycle();
            if(windows!=null)for(AccessibilityWindowInfo window:windows)if(recycled.add(window))window.recycle();
        }
    }
    @Override public Target snapshot(){try(Resolved resolved=resolve(false)){return resolved==null?null:resolved.target;}}
    private boolean same(Target now,Target target){return now!=null&&target!=null&&now.windowId==target.windowId&&now.packageName.equals(target.packageName)&&now.bounds.equals(target.bounds)&&now.windowBounds.equals(target.windowBounds)&&now.displayWidth==target.displayWidth&&now.displayHeight==target.displayHeight;}
    @Override public boolean matches(Target target){
        return same(snapshot(),target);
    }
    @Override public boolean captureSafe(Target target){try(Resolved resolved=resolve(true)){return resolved!=null&&same(resolved.target,target);}}
    @Override public boolean begin(long ticket,Target target,Runnable interrupted){
        if(!CaptureTicket.owns(ticket)||!matches(target))return false;
        end(owner);owner=ticket;this.target=target;this.interrupted=interrupted;return true;
    }
    @Override public boolean forward(long ticket,Target target,Consumer<Boolean> callback){
        if(owner!=ticket||!CaptureTicket.owns(ticket)||pending!=null)return false;
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        try(Resolved resolved=resolve(true)){
            if(resolved==null||!same(resolved.target,target))return false;
            AccessibilityNodeInfo root=resolved.root;
            ArrayDeque<AccessibilityNodeInfo> todo=new ArrayDeque<>();todo.add(root);AccessibilityNodeInfo best=null;long bestArea=0;
            while(!todo.isEmpty()&&nodes.size()<1000){
                AccessibilityNodeInfo node=todo.removeFirst();nodes.add(node);
                Rect bounds=new Rect();node.getBoundsInScreen(bounds);
                String className=node.getClassName()==null?"":node.getClassName().toString();
                boolean down=hasAction(node,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId());
                boolean horizontal=hasAction(node,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId())||hasAction(node,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId());
                boolean vertical=down||!horizontal&&knownVertical(className)&&hasAction(node,AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                boolean locked=verticalBounds==null||verticalBounds.equals(bounds)&&verticalClass.equals(className);
                if(node.getWindowId()==target.windowId&&target.packageName.contentEquals(node.getPackageName()==null?"":node.getPackageName())&&node.isVisibleToUser()&&node.isScrollable()&&vertical&&!className.contains("ViewPager")&&locked&&target.windowBounds.contains(bounds)&&bounds.width()>=target.bounds.width()*.4&&bounds.height()>=target.bounds.height()*.4){
                    long area=(long)bounds.width()*bounds.height();
                    // An inner vertical article takes precedence over an enclosing container.
                    Rect chosen=new Rect();if(best!=null)best.getBoundsInScreen(chosen);
                    if(best==null||chosen.contains(bounds)||!bounds.contains(chosen)&&area>bestArea){best=node;bestArea=area;}
                }
                for(int i=0;i<node.getChildCount()&&todo.size()+nodes.size()<1000;i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)todo.addLast(child);}
            }
            nodes.addAll(todo);if(best==null)return false;
            pending=callback;pendingBounds=new Rect();best.getBoundsInScreen(pendingBounds);pendingSince=SystemClock.uptimeMillis();sawScroll=false;
            verticalBounds=new Rect(pendingBounds);verticalClass=best.getClassName()==null?"":best.getClassName().toString();
            int action=AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            for(AccessibilityNodeInfo.AccessibilityAction item:best.getActionList())if(item.getId()==AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId())action=item.getId();
            handler.postDelayed(deadline,1400);
            Bundle arguments=null;
            if(Build.VERSION.SDK_INT>=35&&best.isGranularScrollingSupported()){arguments=new Bundle();arguments.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_SCROLL_AMOUNT_FLOAT,.7F);}
            if(!best.performAction(action,arguments)){clearPending();return false;}
            return true;
        }catch(RuntimeException e){clearPending();return false;}
        finally{for(int i=1;i<nodes.size();i++)nodes.get(i).recycle();}
    }
    private static boolean hasAction(AccessibilityNodeInfo node,int id){for(AccessibilityNodeInfo.AccessibilityAction action:node.getActionList())if(action.getId()==id)return true;return false;}
    private static boolean knownVertical(String name){return name.equals("android.widget.ScrollView")||name.endsWith(".NestedScrollView")||name.equals("android.widget.ListView")||name.equals("android.widget.ExpandableListView");}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){
        if(owner==0||!CaptureTicket.owns(owner))return;
        int type=event.getEventType();String pkg=event.getPackageName()==null?"":event.getPackageName().toString();
        if(type==AccessibilityEvent.TYPE_TOUCH_INTERACTION_START&&pending!=null){cancel();return;}
        if(pkg.equals(getPackageName())&&knownOverlay.test(event.getWindowId()))return;
        if(type==AccessibilityEvent.TYPE_VIEW_CLICKED){cancel();return;}
        if(type==AccessibilityEvent.TYPE_VIEW_SCROLLED){
            if(pending==null||event.getWindowId()!=target.windowId||!pkg.equals(target.packageName)||event.getEventTime()<pendingSince){cancel();return;}
            if(Build.VERSION.SDK_INT>=28&&event.getScrollDeltaX()!=0&&event.getScrollDeltaY()==0){cancel();return;}
            AccessibilityNodeInfo source=event.getSource();
            try{
                Rect bounds=new Rect();if(source==null){cancel();return;}source.getBoundsInScreen(bounds);
                if(!pendingBounds.equals(bounds)){cancel();return;}
            }finally{if(source!=null)source.recycle();}
            sawScroll=true;handler.removeCallbacks(settled);handler.postDelayed(settled,250);return;
        }
        if(type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED||type==AccessibilityEvent.TYPE_WINDOWS_CHANGED){
            long expected=owner;
            handler.postDelayed(()->{
                if(owner==0||owner!=expected)return;
                if(!matches(target))cancel();
            },150);
        }
    }
    private void finishScroll(boolean moved){
        Consumer<Boolean> callback=pending;if(callback==null)return;
        boolean valid=CaptureTicket.owns(owner)&&matches(target);clearPending();callback.accept(moved&&valid);
    }
    private void clearPending(){handler.removeCallbacks(settled);handler.removeCallbacks(deadline);pending=null;pendingBounds=null;sawScroll=false;}
    private void cancel(){Runnable callback=interrupted;owner=0;target=null;verticalBounds=null;verticalClass=null;interrupted=null;handler.removeCallbacksAndMessages(null);clearPending();if(callback!=null)callback.run();}
    @Override public void end(long ticket){if(owner!=ticket)return;owner=0;target=null;verticalBounds=null;verticalClass=null;interrupted=null;handler.removeCallbacksAndMessages(null);clearPending();}
    @Override public void onInterrupt(){cancel();}
    @Override public boolean onUnbind(Intent intent){cancel();if(current.get()==this)current.clear();return super.onUnbind(intent);}
    @Override public void onDestroy(){cancel();if(current.get()==this)current.clear();super.onDestroy();}
}
