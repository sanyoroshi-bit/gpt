package jp.tenreader.launcher;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;

final class OverlayWindows {
    static Context context(Context service) {
        if (Build.VERSION.SDK_INT < 30) return service;
        Display display = service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) throw new IllegalStateException("Display unavailable");
        return service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
    }
    static Rect safeBounds(Context context) {
        WindowManager manager = context.getSystemService(WindowManager.class);
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = manager.getCurrentWindowMetrics().getBounds();
            Insets insets = manager.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            return new Rect(insets.left,insets.top,bounds.width()-insets.right,bounds.height()-insets.bottom);
        }
        DisplayMetrics metrics = new DisplayMetrics(); manager.getDefaultDisplay().getRealMetrics(metrics);
        int bar = Math.round(32*metrics.density);
        return new Rect(0,bar,metrics.widthPixels,metrics.heightPixels-bar*2);
    }
    static Rect fullBounds(Context context){
        WindowManager manager=context.getSystemService(WindowManager.class);
        if(Build.VERSION.SDK_INT>=30)return manager.getMaximumWindowMetrics().getBounds();
        DisplayMetrics metrics=new DisplayMetrics();manager.getDefaultDisplay().getRealMetrics(metrics);return new Rect(0,0,metrics.widthPixels,metrics.heightPixels);
    }
}
