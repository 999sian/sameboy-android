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
    interface KeyListener { void onKey(int gbKeyIndex, boolean pressed); }

    private final KeyListener listener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // regions computed in onSizeChanged
    private RectF up, down, left, right, a, b, start, select;
    // track which pointer id is over which key
    private final java.util.HashMap<Integer, Integer> pointerKey = new java.util.HashMap<>();

    TouchOverlayView(Context ctx, KeyListener l) { super(ctx); this.listener = l; }

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
        return -1;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int k = keyAt(e.getX(idx), e.getY(idx));
                if (k >= 0) { pointerKey.put(id, k); listener.onKey(k, true); }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int pid = e.getPointerId(i);
                    int newK = keyAt(e.getX(i), e.getY(i));
                    Integer oldK = pointerKey.get(pid);
                    if (oldK == null || oldK != (Integer) newK) {
                        if (oldK != null) listener.onKey(oldK, false);
                        if (newK >= 0) { pointerKey.put(pid, newK); listener.onKey(newK, true); }
                        else pointerKey.remove(pid);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                Integer k = pointerKey.remove(id);
                if (k != null) listener.onKey(k, false);
                break;
            }
        }
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        paint.setColor(Color.argb(90, 255, 255, 255));
        for (RectF r : new RectF[]{up, down, left, right}) if (r != null) c.drawRect(r, paint);
        paint.setColor(Color.argb(120, 200, 60, 90));
        if (a != null) c.drawOval(a, paint);
        if (b != null) c.drawOval(b, paint);
        paint.setColor(Color.argb(110, 180, 180, 180));
        if (start != null) c.drawRoundRect(start, 12, 12, paint);
        if (select != null) c.drawRoundRect(select, 12, 12, paint);
    }
}
