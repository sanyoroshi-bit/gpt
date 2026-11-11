package jp.tenreader.launcher;

import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Only views actually attached by our two overlay owners qualify as reader controls. */
final class OwnOverlayWindows {
    private static final List<WeakReference<View>> roots=new ArrayList<>();
    static void add(View view){remove(view);roots.add(new WeakReference<>(view));}
    static void remove(View view){roots.removeIf(ref->ref.get()==null||ref.get()==view);}
    static boolean contains(int windowId){
        if(windowId<0)return false;
        for(Iterator<WeakReference<View>> it=roots.iterator();it.hasNext();){
            View view=it.next().get();if(view==null){it.remove();continue;}
            if(!view.isAttachedToWindow())continue;
            AccessibilityNodeInfo node=null;
            try{node=view.createAccessibilityNodeInfo();if(node!=null&&node.getWindowId()==windowId)return true;}
            finally{if(node!=null)node.recycle();}
        }
        return false;
    }
    static boolean hidden(){
        for(WeakReference<View> ref:roots){View view=ref.get();if(view!=null&&view.isAttachedToWindow()&&view.isShown())return false;}
        return true;
    }
}
