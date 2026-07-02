package io.sameboy.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/** In-game menu + save/load slot picker. Programmatic UI (no XML), M1 convention.
 *  The host pauses emulation before show() and unpauses in onMenuClosed(). */
final class GameMenuDialog {
    static final int SLOTS = 4;

    interface Host {
        void onMenuClosed();
        void onSaveSlot(int slot);
        void onLoadSlot(int slot);
        void onResetGame();
        void onSwitchModel(int model);
        void onOpenSettings();
        void onExitGame();
        File stateFile(int slot);
        Bitmap thumbnail(int slot);
    }

    private GameMenuDialog() {}

    static void show(Activity a, Host h) {
        final String[] items = { "Resume", "Save state", "Load state", "Reset", "Model", "Settings", "Exit" };
        final boolean[] chained = { false };   // a submenu took over; don't unpause yet
        AlertDialog dlg = new AlertDialog.Builder(a)
            .setTitle("SameBoy")
            .setItems(items, (d, which) -> {
                switch (which) {
                    case 1: chained[0] = true; showSlots(a, h, true); break;
                    case 2: chained[0] = true; showSlots(a, h, false); break;
                    case 3: h.onResetGame(); break;
                    case 4: chained[0] = true; showModels(a, h); break;
                    case 5: chained[0] = true; h.onOpenSettings(); return;   // leaves menu; EmulatorActivity re-applies on resume
                    case 6: h.onExitGame(); return;
                    default: break;                   // 0 = Resume: just dismiss
                }
            })
            .create();
        dlg.setOnDismissListener(d -> { if (!chained[0]) h.onMenuClosed(); });
        dlg.show();
    }

    private static void showModels(Activity a, Host h) {
        final String[] names = { "Game Boy (DMG)", "Game Boy Color (CGB)", "Game Boy Advance (AGB)" };
        final int[] models = { NativeBridge.MODEL_DMG_B, NativeBridge.MODEL_CGB_E, NativeBridge.MODEL_AGB };
        AlertDialog dlg = new AlertDialog.Builder(a)
            .setTitle("Model (reboots the game)")
            .setItems(names, (d, which) -> h.onSwitchModel(models[which]))
            .create();
        dlg.setOnDismissListener(d -> h.onMenuClosed());
        dlg.show();
    }

    private static void showSlots(Activity a, Host h, boolean forSave) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (a.getResources().getDisplayMetrics().density * 12);
        col.setPadding(pad, pad, pad, pad);

        AlertDialog dlg = new AlertDialog.Builder(a)
            .setTitle(forSave ? "Save to slot" : "Load from slot")
            .setView(col)
            .setNegativeButton("Cancel", null)
            .create();

        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            File f = h.stateFile(slot);
            boolean exists = f.exists();

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, pad / 2, 0, pad / 2);

            ImageView thumb = new ImageView(a);
            int tw = (int) (a.getResources().getDisplayMetrics().density * 64);
            thumb.setLayoutParams(new LinearLayout.LayoutParams(tw, tw * 144 / 160));
            thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
            thumb.setBackgroundColor(Color.DKGRAY);
            Bitmap bmp = exists ? h.thumbnail(slot) : null;
            if (bmp != null) thumb.setImageBitmap(bmp);
            row.addView(thumb);

            LinearLayout text = new LinearLayout(a);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(pad, 0, 0, 0);
            TextView title = new TextView(a);
            title.setText("Slot " + (slot + 1));
            TextView sub = new TextView(a);
            sub.setText(exists
                ? DateUtils.getRelativeTimeSpanString(f.lastModified()).toString()
                : "Empty");
            text.addView(title);
            text.addView(sub);
            row.addView(text);

            boolean enabled = forSave || exists;
            row.setEnabled(enabled);
            title.setEnabled(enabled);
            row.setAlpha(enabled ? 1f : 0.4f);
            if (enabled) {
                row.setOnClickListener(v -> {
                    if (forSave) h.onSaveSlot(slot);
                    else h.onLoadSlot(slot);
                    dlg.dismiss();
                });
            }
            col.addView(row);
        }

        dlg.setOnDismissListener(d -> h.onMenuClosed());
        dlg.show();
    }
}
