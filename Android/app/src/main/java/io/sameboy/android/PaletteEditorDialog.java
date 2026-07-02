package io.sameboy.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Custom 4-shade DMG palette editor. Shade 0 = darkest .. 3 = lightest. */
final class PaletteEditorDialog {
    private PaletteEditorDialog() {}

    static void show(Activity a, Settings s, Runnable onApplied) {
        int dp = (int) (a.getResources().getDisplayMetrics().density * 12);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp, dp, dp, dp);

        final int[] colors = new int[4];
        final View[] swatches = new View[4];
        final String[] labels = { "Shade 0 (darkest)", "Shade 1", "Shade 2", "Shade 3 (lightest)" };
        for (int i = 0; i < 4; i++) {
            colors[i] = s.customColor(i);
            final int idx = i;
            TextView label = new TextView(a);
            label.setText(labels[i]);
            label.setPadding(0, dp, 0, 0);
            col.addView(label);

            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            View sw = new View(a);
            int sz = (int) (a.getResources().getDisplayMetrics().density * 40);
            sw.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            sw.setBackgroundColor(0xFF000000 | colors[i]);
            swatches[i] = sw;
            row.addView(sw);

            LinearLayout sliders = new LinearLayout(a);
            sliders.setOrientation(LinearLayout.VERTICAL);
            sliders.setPadding(dp, 0, 0, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            sliders.setLayoutParams(lp);
            addChannel(a, sliders, colors, idx, 16, swatches[i]);  // R shift 16
            addChannel(a, sliders, colors, idx, 8, swatches[i]);   // G shift 8
            addChannel(a, sliders, colors, idx, 0, swatches[i]);   // B shift 0
            row.addView(sliders);
            col.addView(row);
        }

        new AlertDialog.Builder(a)
            .setTitle("Custom palette")
            .setView(wrapScroll(a, col))
            .setPositiveButton("Apply", (d, w) -> {
                for (int i = 0; i < 4; i++) s.setCustomColor(i, colors[i]);
                s.setPaletteBuiltin(-1);
                if (onApplied != null) onApplied.run();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private static void addChannel(Activity a, LinearLayout parent, int[] colors, int idx, int shift, View swatch) {
        SeekBar bar = new SeekBar(a);
        bar.setMax(255);
        bar.setProgress((colors[idx] >> shift) & 0xFF);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                colors[idx] = (colors[idx] & ~(0xFF << shift)) | (progress << shift);
                swatch.setBackgroundColor(0xFF000000 | colors[idx]);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        parent.addView(bar);
    }

    private static View wrapScroll(Activity a, View content) {
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(content);
        return sv;
    }
}
