package io.sameboy.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Minimal, functional on-screen controls. Art polish is a later milestone;
 *  M1 draws simple hit-regions and reports presses via the callback. */
class TouchOverlayView extends View {
    interface ControlListener {
        void onKey(int gbKeyIndex, boolean pressed);      // 0..7, unchanged semantics
        void onSpecial(int what, boolean pressed);        // SPECIAL_* below
    }
    static final int SPECIAL_REWIND = 8, SPECIAL_TURBO = 9, SPECIAL_MENU = 10;

    private final ControlListener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // regions computed in onSizeChanged
    private RectF up, down, left, right, a, b, start, select, rewind, turbo, menu;
    // per-pointer key assignment + per-key refcount (multiple fingers may hold one key)
    private final java.util.HashMap<Integer, Integer> pointerKey = new java.util.HashMap<>();
    private final int[] keyCount = new int[11]; // GB_KEY_MAX + specials
    private float opacity = 0.6f;
    private boolean haptics = true;

    TouchOverlayView(Context ctx, ControlListener l) { super(ctx); this.listener = l; }

    void setOpacity(float a) { opacity = a; invalidate(); }
    void setHaptics(boolean on) { haptics = on; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float u = Math.min(w, h) / 10f;            // unit
        float dpadCx = u * 2.2f, dpadCy = h - u * 3f;
        up     = new RectF(dpadCx - u/2, dpadCy - u*1.5f, dpadCx + u/2, dpadCy - u/2);
        down   = new RectF(dpadCx - u/2, dpadCy + u/2,    dpadCx + u/2, dpadCy + u*1.5f);
        left   = new RectF(dpadCx - u*1.5f, dpadCy - u/2, dpadCx - u/2, dpadCy + u/2);
        right  = new RectF(dpadCx + u/2, dpadCy - u/2,    dpadCx + u*1.5f, dpadCy + u/2);
        a      = new RectF(w - u*1.8f, dpadCy - u*0.8f, w - u*0.4f, dpadCy + u*0.6f);
        b      = new RectF(w - u*3.3f, dpadCy + u*0.2f, w - u*1.9f, dpadCy + u*1.6f);
        start  = new RectF(w/2f + u*0.2f, h - u*1.4f, w/2f + u*2.2f, h - u*0.4f);
        select = new RectF(w/2f - u*2.2f, h - u*1.4f, w/2f - u*0.2f, h - u*0.4f);
        rewind = new RectF(dpadCx - u, dpadCy - u*3.4f, dpadCx + u, dpadCy - u*2.4f);
        turbo  = new RectF(w - u*2.9f, dpadCy - u*3.0f, w - u*0.9f, dpadCy - u*2.0f);
        menu   = new RectF(w - u*1.5f, u*0.4f, w - u*0.4f, u*1.5f);
    }

    private int keyAt(float x, float y) {
        if (up.contains(x, y)) return NativeBridge.KEY_UP;
        if (down.contains(x, y)) return NativeBridge.KEY_DOWN;
        if (left.contains(x, y)) return NativeBridge.KEY_LEFT;
        if (right.contains(x, y)) return NativeBridge.KEY_RIGHT;
        if (a.contains(x, y)) return NativeBridge.KEY_A;
        if (b.contains(x, y)) return NativeBridge.KEY_B;
        if (start.contains(x, y)) return NativeBridge.KEY_START;
        if (select.contains(x, y)) return NativeBridge.KEY_SELECT;
        if (rewind.contains(x, y)) return SPECIAL_REWIND;
        if (turbo.contains(x, y)) return SPECIAL_TURBO;
        if (menu.contains(x, y)) return SPECIAL_MENU;
        return -1;
    }

    private void press(int pointerId, int k) {
        Integer old = pointerKey.get(pointerId);
        if (old != null && old == k) return;            // already on this key
        if (old != null) releasePointer(pointerId);     // moved off previous key
        pointerKey.put(pointerId, k);
        if (k == SPECIAL_MENU) {
            if (haptics) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            listener.onSpecial(SPECIAL_MENU, true);
            return;
        }
        if (keyCount[k]++ == 0) {
            if (haptics) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (k < 8) listener.onKey(k, true);
            else listener.onSpecial(k, true);
        }
    }

    private void releasePointer(int pointerId) {
        Integer k = pointerKey.remove(pointerId);
        if (k != null) {
            if (k == SPECIAL_MENU) return;
            if (--keyCount[k] == 0) {
                if (k < 8) listener.onKey(k, false);
                else listener.onSpecial(k, false);
            }
        }
    }

    private void releaseAll() {
        for (Integer k : pointerKey.values()) {
            if (k == SPECIAL_MENU) continue;
            if (--keyCount[k] == 0) {
                if (k < 8) listener.onKey(k, false);
                else listener.onSpecial(k, false);
            }
        }
        pointerKey.clear();
        for (int i = 0; i < keyCount.length; i++) keyCount[i] = 0; // belt-and-suspenders
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                int k = keyAt(e.getX(idx), e.getY(idx));
                if (k >= 0) press(id, k);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int pid = e.getPointerId(i);
                    int newK = keyAt(e.getX(i), e.getY(i));
                    Integer oldK = pointerKey.get(pid);
                    int oldVal = (oldK == null) ? -1 : oldK;
                    if (oldVal == newK) continue;         // no change for this finger
                    if (newK >= 0) press(pid, newK);      // press() releases the old key first
                    else releasePointer(pid);             // slid off all buttons
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                releasePointer(e.getPointerId(e.getActionIndex()));
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                releaseAll();                              // cancel kills ALL pointers
                break;
            }
        }
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        paint.setColor(Color.argb((int) (90 * opacity), 255, 255, 255));
        for (RectF r : new RectF[]{up, down, left, right}) if (r != null) c.drawRect(r, paint);
        paint.setColor(Color.argb((int) (120 * opacity), 200, 60, 90));
        if (a != null) c.drawOval(a, paint);
        if (b != null) c.drawOval(b, paint);
        paint.setColor(Color.argb((int) (110 * opacity), 180, 180, 180));
        if (start != null) c.drawRoundRect(start, 12, 12, paint);
        if (select != null) c.drawRoundRect(select, 12, 12, paint);
        if (rewind != null) c.drawRoundRect(rewind, 12, 12, paint);
        if (turbo != null) c.drawRoundRect(turbo, 12, 12, paint);
        if (menu != null) c.drawRoundRect(menu, 12, 12, paint);
        paint.setColor(Color.argb((int) (200 * opacity), 255, 255, 255));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(rewind != null ? rewind.height() * 0.6f : 24);
        if (rewind != null) c.drawText("<<", rewind.centerX(), rewind.centerY() + rewind.height() * 0.2f, paint);
        if (turbo != null) c.drawText(">>", turbo.centerX(), turbo.centerY() + turbo.height() * 0.2f, paint);
        if (menu != null) c.drawText("=", menu.centerX(), menu.centerY() + menu.height() * 0.2f, paint);
    }
}
