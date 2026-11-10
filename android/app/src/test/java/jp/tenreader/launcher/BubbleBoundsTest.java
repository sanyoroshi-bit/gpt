package jp.tenreader.launcher;

import org.junit.Test;
import static org.junit.Assert.*;

public class BubbleBoundsTest {
    @Test public void draggingStaysInsideBarsAndCutout() {
        BubbleBounds bounds = new BubbleBounds(1080, 2400, 24, 90, 24, 120, 168);
        assertEquals(24, bounds.clampX(-1000));
        assertEquals(888, bounds.clampX(5000));
        assertEquals(90, bounds.clampY(-1000));
        assertEquals(2112, bounds.clampY(5000));
        assertEquals(300, bounds.clampX(300));
    }
    @Test public void rotationBringsOldPositionBackOnScreen() {
        BubbleBounds landscape = new BubbleBounds(2400, 1080, 90, 24, 120, 24, 168);
        assertEquals(888, landscape.clampY(2112));
        assertEquals(888, landscape.clampX(888));
    }
    @Test public void tinyWindowNeverInvertsItsBounds() {
        BubbleBounds tiny = new BubbleBounds(100, 100, 5, 10, 5, 10, 168);
        assertEquals(5, tiny.clampX(Integer.MAX_VALUE));
        assertEquals(10, tiny.clampY(Integer.MIN_VALUE));
    }
    @Test public void tapJitterStaysBelowDragThreshold() {
        assertFalse(BubbleBounds.isDrag(3, 4, 8));
        assertFalse(BubbleBounds.isDrag(8, 0, 8));
        assertTrue(BubbleBounds.isDrag(6, 6, 8));
        assertTrue(BubbleBounds.isDrag(-20, 0, 8));
    }
}
