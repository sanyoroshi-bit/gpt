package jp.tenreader.launcher;

/** Pixel bounds for the bubble's top-left corner, including after rotation. */
final class BubbleBounds {
    final int left, top, right, bottom;
    BubbleBounds(int width, int height, int insetLeft, int insetTop, int insetRight, int insetBottom, int size) {
        left = Math.max(0, insetLeft);
        top = Math.max(0, insetTop);
        right = Math.max(left, width - Math.max(0, insetRight) - size);
        bottom = Math.max(top, height - Math.max(0, insetBottom) - size);
    }
    int clampX(int x) { return Math.max(left, Math.min(right, x)); }
    int clampY(int y) { return Math.max(top, Math.min(bottom, y)); }
    static boolean isDrag(float dx, float dy, int slop) { return dx * dx + dy * dy > (double) slop * slop; }
}
