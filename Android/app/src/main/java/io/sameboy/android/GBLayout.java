package io.sameboy.android;

import android.graphics.PointF;
import android.graphics.RectF;

/** Console-body geometry for the emulator screen, ported from the iOS port's
 *  GBVerticalLayout/GBHorizontalLayout (points -> dp -> px). All outputs px. */
final class GBLayout {
    final RectF screenRect = new RectF();
    final float bezelWidth;
    final PointF dpad = new PointF(), a = new PointF(), b = new PointF();
    final PointF select = new PointF(), start = new PointF();
    final PointF rewind = new PointF(), turbo = new PointF(), menu = new PointF();
    final boolean landscape;
    boolean drawLogo = false;
    float logoY, logoSize;

    GBLayout(int w, int h, float dp) {
        landscape = w > h;
        if (landscape) {
            // height-filling integer-scaled screen, >=164dp wings for controls
            float sh = (float) Math.floor(h / 144f) * 144f;
            if (sh == 0) sh = h;
            float sw = sh / 144f * 160f;
            while ((w - sw) / 2f < 164f * dp && sh > 144f) { sh -= 144f; sw -= 160f; }
            float border = Math.min(sw / 40f, 16f * dp);
            float x = (w - sw) / 2f;
            float vMargin = (h - sh) / 2f;
            float y;
            if (vMargin * 2f > border * 7f) { drawLogo = true; y = (h - sh - border * 5f) / 2f; }
            else y = vMargin;
            screenRect.set(x, y, x + sw, y + sh);
            bezelWidth = border;
            if (drawLogo) { logoSize = 24f * dp; logoY = screenRect.bottom + border * 2f; }

            dpad.set(Math.round((x - border) / 2f), Math.round(h * 3f / 8f));
            float buttonsCx = (w + sw + x) / 2f;
            PointF delta = buttonDelta((w - sw) / 2f - border * 5f - 36f * dp * 2f, dp);
            a.set(Math.round(buttonsCx + delta.x / 2f), Math.round(dpad.y - delta.y / 2f));
            b.set(Math.round(buttonsCx - delta.x / 2f), Math.round(dpad.y + delta.y / 2f));
            select.set(dpad.x, Math.min(Math.round(h * 3f / 4f), dpad.y + 180f * dp));
            start.set(buttonsCx, select.y);
            rewind.set(select.x, Math.min(h - 28f * dp, select.y + 64f * dp));
            turbo.set(start.x, rewind.y);
            menu.set(w - 40f * dp, 40f * dp);
        } else {
            // width-filling integer-scaled screen, top-anchored.
            // Short screens (h < ~700dp): shrink by integer steps until the d-pad
            // clears the screen well (landscape has the same loop; iOS portrait
            // never needed it because no iPhone is this short).
            float sw = (float) Math.floor(w / 160f) * 160f;
            if (sw == 0) sw = w;
            float sh, border, topInset, y, controlAreaStart, selectY, dpadY;
            while (true) {
                sh = sw / 160f * 144f;
                border = Math.min(sw / 40f, 16f * dp);
                topInset = Math.min(border * 2f, 20f * dp);
                y = topInset + 24f * dp;               // status-bar breathing room
                controlAreaStart = y + sh + topInset;
                selectY = Math.min(h - 80f * dp, (h - controlAreaStart) * 0.75f + controlAreaStart);
                dpadY = selectY - 140f * dp;
                // d-pad sprite is 151dp tall; keep its top clear of the bezel
                if (dpadY - 78f * dp >= y + sh + border || sw <= 160f) break;
                sw -= 160f;
            }
            float x = (w - sw) / 2f;
            screenRect.set(x, y, x + sw, y + sh);
            bezelWidth = border;

            select.set(Math.min(w / 4f, 120f * dp), selectY);
            start.set(w - select.x, select.y);
            dpad.set(select.x, dpadY);
            float buttonsCx = w - dpad.x;
            PointF delta = buttonDelta(w / 2f - 36f * dp * 2f - border * 2f, dp);
            a.set(Math.round(buttonsCx + delta.x / 2f), Math.round(dpad.y - delta.y / 2f));
            b.set(Math.round(buttonsCx - delta.x / 2f), Math.round(dpad.y + delta.y / 2f));

            float controlsTop = dpad.y - 80f * dp;
            if (controlsTop - controlAreaStart > 24f * dp + border * 2f) {
                drawLogo = true; logoSize = 24f * dp; logoY = controlAreaStart + border;
            }
            // [<<] [=] [>>] pill row centered between Select and Start
            float pillY = Math.min(h - 24f * dp, select.y + 56f * dp);
            rewind.set(w / 2f - 64f * dp, pillY);
            menu.set(w / 2f, pillY);
            turbo.set(w / 2f + 64f * dp, pillY);
        }
    }

    /** iOS buttonDeltaForMaxHorizontalDistance: default 90x45dp, squeezed onto a 100dp circle. */
    private static PointF buttonDelta(float maxDist, float dp) {
        float wd = 90f * dp, hd = 45f * dp;
        if (wd <= maxDist) return new PointF(wd, hd);
        float w = Math.max(maxDist, 40f * dp);
        float r = 100f * dp;
        return new PointF(w, (float) Math.floor(Math.sqrt(Math.max(0, r * r - w * w))));
    }
}
