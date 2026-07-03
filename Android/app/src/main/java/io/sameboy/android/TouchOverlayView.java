package io.sameboy.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** Game Boy console body + touch controls, iOS-port look (GBBackgroundView).
 *  Draws body gradient, screen bezel (SurfaceView shows through the well),
 *  official sprites with pressed states, rotated navy labels. 8-way d-pad. */
class TouchOverlayView extends View {
    interface ControlListener {
        void onKey(int gbKey, boolean pressed);
        void onSpecial(int what, boolean pressed);
    }
    interface ScreenRectListener { void onScreenRect(RectF r); }
    static final int SPECIAL_REWIND = 8, SPECIAL_TURBO = 9, SPECIAL_MENU = 10;

    // theme (iOS GBTheme default)
    private static final int BODY_TOP = 0xFFC0C3C7, BODY_BOTTOM = 0xFFAEB0B4;
    private static final int BEZEL_TOP = 0xFF353535, BEZEL_BOTTOM = 0xFF2D2D2D;
    private static final int BRAND = 0xFF00468D;

    private final ControlListener listener;
    private final ScreenRectListener rectListener;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final float dp = getResources().getDisplayMetrics().density;

    private GBLayout layout;
    private Bitmap dpadBmp, dpadShadow, dpadShadowDiag, buttonBmp, buttonPressedBmp, button2Bmp, button2PressedBmp;

    // input state: per-pointer key MASK (bit i = key i held by that pointer; specials use their own bits)
    private final java.util.HashMap<Integer, Integer> pointerMask = new java.util.HashMap<>();
    private final int[] keyCount = new int[11];
    private float opacity = 0.6f;
    private boolean haptics = true;
    private boolean controlsHidden = false;

    TouchOverlayView(Context ctx, ControlListener l, ScreenRectListener r) {
        super(ctx);
        listener = l;
        rectListener = r;
        dpadBmp = BitmapFactory.decodeResource(getResources(), R.drawable.gb_dpad);
        dpadShadow = BitmapFactory.decodeResource(getResources(), R.drawable.gb_dpad_shadow);
        dpadShadowDiag = BitmapFactory.decodeResource(getResources(), R.drawable.gb_dpad_shadow_diag);
        buttonBmp = BitmapFactory.decodeResource(getResources(), R.drawable.gb_button);
        buttonPressedBmp = BitmapFactory.decodeResource(getResources(), R.drawable.gb_button_pressed);
        button2Bmp = BitmapFactory.decodeResource(getResources(), R.drawable.gb_button2);
        button2PressedBmp = BitmapFactory.decodeResource(getResources(), R.drawable.gb_button2_pressed);
    }

    void setOpacity(float a) { opacity = a; invalidate(); }
    void setHaptics(boolean on) { haptics = on; }
    void setControlsHidden(boolean hidden) { controlsHidden = hidden; invalidate(); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        layout = new GBLayout(w, h, dp);
        if (rectListener != null) rectListener.onScreenRect(new RectF(layout.screenRect));
        invalidate();
    }

    // ---------- hit testing ----------

    private static final int MASK_REWIND = 1 << SPECIAL_REWIND, MASK_TURBO = 1 << SPECIAL_TURBO,
                             MASK_MENU = 1 << SPECIAL_MENU;

    /** 8-way d-pad + round buttons -> key mask for a touch point. */
    private int maskAt(float x, float y) {
        if (layout == null) return 0;
        if (dist2(x, y, layout.menu) < sq(30f * dp)) return MASK_MENU;
        if (controlsHidden) return 0;
        if (dist2(x, y, layout.rewind) < sq(34f * dp)) return MASK_REWIND;
        if (dist2(x, y, layout.turbo) < sq(34f * dp)) return MASK_TURBO;
        if (dist2(x, y, layout.a) < sq(44f * dp)) return 1 << NativeBridge.KEY_A;
        if (dist2(x, y, layout.b) < sq(44f * dp)) return 1 << NativeBridge.KEY_B;
        if (dist2(x, y, layout.select) < sq(38f * dp)) return 1 << NativeBridge.KEY_SELECT;
        if (dist2(x, y, layout.start) < sq(38f * dp)) return 1 << NativeBridge.KEY_START;
        float dx = x - layout.dpad.x, dy = y - layout.dpad.y;
        float half = 92f * dp;                          // generous dpad square
        if (Math.abs(dx) < half && Math.abs(dy) < half) {
            if (dist2(x, y, layout.dpad) < sq(14f * dp)) return 0;   // dead center
            int m = 0;
            double ang = Math.toDegrees(Math.atan2(-dy, dx));        // 0=right, CCW
            if (ang < 0) ang += 360;
            if (ang < 67.5 || ang > 292.5) m |= 1 << NativeBridge.KEY_RIGHT;
            if (ang > 112.5 && ang < 247.5) m |= 1 << NativeBridge.KEY_LEFT;
            if (ang > 22.5 && ang < 157.5) m |= 1 << NativeBridge.KEY_UP;
            if (ang > 202.5 && ang < 337.5) m |= 1 << NativeBridge.KEY_DOWN;
            return m;
        }
        return 0;
    }

    private static float sq(float v) { return v * v; }
    private static float dist2(float x, float y, PointF p) {
        float dx = x - p.x, dy = y - p.y; return dx * dx + dy * dy;
    }

    // ---------- press bookkeeping (mask-based; refcount semantics preserved) ----------

    private void applyMask(int pointerId, int newMask) {
        Integer boxed = pointerMask.get(pointerId);
        int oldMask = boxed == null ? 0 : boxed;
        if (oldMask == newMask) return;
        int added = newMask & ~oldMask, removed = oldMask & ~newMask;
        boolean buzzed = false;
        for (int k = 0; k <= SPECIAL_MENU; k++) {
            int bit = 1 << k;
            if ((added & bit) != 0) {
                if (k == SPECIAL_MENU) {
                    if (haptics && !buzzed) { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); buzzed = true; }
                    listener.onSpecial(SPECIAL_MENU, true);      // one-shot, not refcounted
                } else if (keyCount[k]++ == 0) {
                    if (haptics && !buzzed) { performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); buzzed = true; }
                    if (k < 8) listener.onKey(k, true); else listener.onSpecial(k, true);
                }
            }
            if ((removed & bit) != 0 && k != SPECIAL_MENU) {
                if (--keyCount[k] == 0) {
                    if (k < 8) listener.onKey(k, false); else listener.onSpecial(k, false);
                }
            }
        }
        if (newMask == 0) pointerMask.remove(pointerId); else pointerMask.put(pointerId, newMask);
        invalidate();
    }

    private void releaseAll() {
        for (Integer id : new java.util.ArrayList<>(pointerMask.keySet())) applyMask(id, 0);
        for (int i = 0; i < keyCount.length; i++) keyCount[i] = 0;
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                applyMask(e.getPointerId(idx), maskAt(e.getX(idx), e.getY(idx)));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int m = maskAt(e.getX(i), e.getY(i));
                    Integer old = pointerMask.get(e.getPointerId(i));
                    // menu is one-shot: never re-fire it on move
                    if (old != null && (old & MASK_MENU) != 0) m |= MASK_MENU;
                    applyMask(e.getPointerId(i), m);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                applyMask(e.getPointerId(e.getActionIndex()), 0);
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
        }
        return true;
    }

    // ---------- drawing ----------

    private int heldMask() {
        int m = 0;
        for (int k = 0; k < 8; k++) if (keyCount[k] > 0) m |= 1 << k;
        return m;
    }

    @Override protected void onDraw(Canvas c) {
        if (layout == null) return;
        int w = getWidth(), h = getHeight();

        // body gradient around the screen well
        paint.setShader(new LinearGradient(0, 0, 0, h, BODY_TOP, BODY_BOTTOM, Shader.TileMode.CLAMP));
        c.save();
        c.clipOutRect(layout.screenRect);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // bezel: rounded rect ring around the screen
        RectF bez = new RectF(layout.screenRect);
        bez.inset(-layout.bezelWidth, -layout.bezelWidth);
        paint.setShader(new LinearGradient(0, bez.top, 0, bez.bottom, BEZEL_TOP, BEZEL_BOTTOM, Shader.TileMode.CLAMP));
        c.drawRoundRect(bez, layout.bezelWidth, layout.bezelWidth, paint);
        paint.setShader(null);
        c.restore();

        // labels + wordmark on the body
        paint.setColor(BRAND);
        if (layout.drawLogo) {
            paint.setTypeface(Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD_ITALIC));
            paint.setTextSize(layout.logoSize);
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("SAMEBOY", w / 2f, layout.logoY + layout.logoSize, paint);
        }
        if (!controlsHidden) {
            drawRotatedLabel(c, "A", layout.a, 24f * dp, 40f * dp, Typeface.DEFAULT_BOLD);
            drawRotatedLabel(c, "B", layout.b, 24f * dp, 40f * dp, Typeface.DEFAULT_BOLD);
            drawRotatedLabel(c, "SELECT", layout.select, 14f * dp, 30f * dp, Typeface.DEFAULT_BOLD);
            drawRotatedLabel(c, "START", layout.start, 14f * dp, 30f * dp, Typeface.DEFAULT_BOLD);
        }

        // controls layer (respects opacity setting)
        int alpha = (int) (opacity * 255);
        spritePaint.setAlpha(alpha);
        if (!controlsHidden) {
            int held = heldMask();
            // d-pad + rotated shadow
            drawSpriteCentered(c, dpadBmp, layout.dpad, 147f * dp, 151f * dp);
            int dmask = held & (1 << NativeBridge.KEY_RIGHT | 1 << NativeBridge.KEY_LEFT
                              | 1 << NativeBridge.KEY_UP | 1 << NativeBridge.KEY_DOWN);
            float rot = Float.NaN; boolean diag = false;
            if (dmask == (1 << NativeBridge.KEY_RIGHT)) rot = 0;
            else if (dmask == (1 << NativeBridge.KEY_RIGHT | 1 << NativeBridge.KEY_DOWN)) { rot = 0; diag = true; }
            else if (dmask == (1 << NativeBridge.KEY_DOWN)) rot = 90;
            else if (dmask == (1 << NativeBridge.KEY_LEFT | 1 << NativeBridge.KEY_DOWN)) { rot = 90; diag = true; }
            else if (dmask == (1 << NativeBridge.KEY_LEFT)) rot = 180;
            else if (dmask == (1 << NativeBridge.KEY_LEFT | 1 << NativeBridge.KEY_UP)) { rot = 180; diag = true; }
            else if (dmask == (1 << NativeBridge.KEY_UP)) rot = -90;
            else if (dmask == (1 << NativeBridge.KEY_RIGHT | 1 << NativeBridge.KEY_UP)) { rot = -90; diag = true; }
            if (!Float.isNaN(rot)) {
                c.save();
                c.rotate(rot, layout.dpad.x, layout.dpad.y);
                drawSpriteCentered(c, diag ? dpadShadowDiag : dpadShadow, layout.dpad, 147f * dp, 147f * dp);
                c.restore();
            }
            // A/B + Select/Start with pressed swaps
            drawSpriteCentered(c, (held & 1 << NativeBridge.KEY_A) != 0 ? buttonPressedBmp : buttonBmp, layout.a, 75f * dp, 79f * dp);
            drawSpriteCentered(c, (held & 1 << NativeBridge.KEY_B) != 0 ? buttonPressedBmp : buttonBmp, layout.b, 75f * dp, 79f * dp);
            drawSpriteCentered(c, (held & 1 << NativeBridge.KEY_SELECT) != 0 ? button2PressedBmp : button2Bmp, layout.select, 76f * dp, 76f * dp);
            drawSpriteCentered(c, (held & 1 << NativeBridge.KEY_START) != 0 ? button2PressedBmp : button2Bmp, layout.start, 76f * dp, 76f * dp);
            // rewind/turbo pills
            drawPill(c, layout.rewind, "<<", keyCount[SPECIAL_REWIND] > 0, alpha);
            drawPill(c, layout.turbo, ">>", keyCount[SPECIAL_TURBO] > 0, alpha);
        }
        drawPill(c, layout.menu, "\u2261", false, alpha);
        spritePaint.setAlpha(255);
    }

    private void drawSpriteCentered(Canvas c, Bitmap bmp, PointF center, float wDp, float hDp) {
        RectF dst = new RectF(center.x - wDp / 2, center.y - hDp / 2, center.x + wDp / 2, center.y + hDp / 2);
        c.drawBitmap(bmp, null, dst, spritePaint);
    }

    private void drawPill(Canvas c, PointF center, String glyph, boolean pressed, int alpha) {
        float rw = 28f * dp, rh = 18f * dp;
        RectF r = new RectF(center.x - rw, center.y - rh, center.x + rw, center.y + rh);
        paint.setColor(Color.argb((int) (alpha * (pressed ? 0.85f : 0.55f)), 45, 45, 45));
        c.drawRoundRect(r, rh, rh, paint);
        paint.setColor(Color.argb(alpha, 192, 195, 199));
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(rh);
        c.drawText(glyph, center.x, center.y + rh * 0.35f, paint);
    }

    private void drawRotatedLabel(Canvas c, String text, PointF origin, float sizePx, float distPx, Typeface face) {
        c.save();
        c.rotate(-30f, origin.x, origin.y);
        paint.setColor(BRAND);
        paint.setTypeface(face);
        paint.setTextSize(sizePx);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, origin.x, origin.y + distPx + sizePx, paint);
        c.restore();
    }
}
