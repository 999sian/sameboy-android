package io.sameboy.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Hand-rolled programmatic settings screen (no XML, no AndroidX Preference).
 *  Writes SharedPreferences via Settings; EmulatorActivity applies on resume. */
public class SettingsActivity extends Activity {
    private Settings s;
    private int dp(int v) { return (int) (getResources().getDisplayMetrics().density * v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        s = new Settings(this);
        setTitle(R.string.settings);

        ScrollView scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(col);

        section(col, "Emulation");
        enumRow(col, "Model (next launch)",
            new String[]{ "Game Boy (DMG)", "Game Boy Color (CGB)", "Game Boy Advance (AGB)" },
            modelToIndex(s.model()), i -> s.setModel(indexToModel(i)));
        sliderRow(col, "Rewind length", 0, 600, s.rewindSeconds(), " s", v -> s.setRewindSeconds(v));
        enumRow(col, "RTC mode", new String[]{ "Sync to host", "Accurate" }, s.rtcMode(), i -> s.setRtcMode(i));
        sliderRow(col, "Turbo cap (0 = uncapped)", 0, 32, s.turboCapQuarters(), " /4x", v -> s.setTurboCapQuarters(v));

        section(col, "Video");
        enumRow(col, "Color correction", new String[]{
            "Disabled", "Correct Curves", "Modern Balanced", "Modern Boost Contrast",
            "Reduce Contrast", "Low Contrast", "Modern Accurate" },
            s.colorCorrection(), i -> s.setColorCorrection(i));
        sliderRow(col, "Light temperature", 0, 20, s.lightSlider(), "", v -> s.setLightSlider(v));
        enumRow(col, "Border", new String[]{ "SGB", "Never", "Always" }, s.borderMode(), i -> s.setBorderMode(i));

        section(col, "Audio");
        sliderRow(col, "Volume", 0, 100, s.volumePct(), " %", v -> s.setVolumePct(v));
        enumRow(col, "High-pass filter", new String[]{ "Off", "Accurate", "Remove DC offset" },
            s.highpass(), i -> s.setHighpass(i));
        sliderRow(col, "Interference", 0, 100, s.interferencePct(), " %", v -> s.setInterferencePct(v));

        section(col, "Controls");
        sliderRow(col, "Button opacity", 0, 100, s.buttonOpacityPct(), " %", v -> s.setButtonOpacityPct(v));
        toggleRow(col, "Haptics", s.haptics(), v -> s.setHaptics(v));

        setContentView(scroll);
    }

    private interface IntSink { void set(int v); }
    private interface BoolSink { void set(boolean v); }

    private void section(LinearLayout col, String title) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(18);
        t.setPadding(0, dp(16), 0, dp(8));
        col.addView(t);
    }

    private void enumRow(LinearLayout col, String label, String[] options, int current, IntSink sink) {
        TextView row = new TextView(this);
        row.setPadding(0, dp(10), 0, dp(10));
        final int[] cur = { current };
        Runnable render = () -> row.setText(label + ":  " + options[Math.max(0, Math.min(options.length - 1, cur[0]))]);
        render.run();
        row.setOnClickListener(v -> new AlertDialog.Builder(this)
            .setTitle(label)
            .setSingleChoiceItems(options, cur[0], (d, which) -> {
                cur[0] = which; sink.set(which); render.run(); d.dismiss();
            })
            .show());
        col.addView(row);
    }

    private void sliderRow(LinearLayout col, String label, int min, int max, int current, String unit, IntSink sink) {
        TextView t = new TextView(this);
        t.setPadding(0, dp(10), 0, 0);
        final int[] cur = { current };
        Runnable render = () -> t.setText(label + ":  " + cur[0] + unit);
        render.run();
        col.addView(t);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(current - min);
        final boolean[] touchTracking = { false };
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                cur[0] = min + progress; render.run();
                // d-pad / keyboard / accessibility changes fire only this callback (no touch
                // tracking); commit those immediately. Touch drags commit on stop instead.
                if (fromUser && !touchTracking[0]) sink.set(cur[0]);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { touchTracking[0] = true; }
            @Override public void onStopTrackingTouch(SeekBar sb) { touchTracking[0] = false; sink.set(cur[0]); }
        });
        col.addView(bar);
    }

    private void toggleRow(LinearLayout col, String label, boolean current, BoolSink sink) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        TextView t = new TextView(this);
        t.setText(label);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(current);
        sw.setOnCheckedChangeListener((btn, checked) -> sink.set(checked));
        row.addView(t);
        row.addView(sw);
        col.addView(row);
    }

    private static int modelToIndex(int model) {
        if (model == NativeBridge.MODEL_DMG_B) return 0;
        if (model == NativeBridge.MODEL_AGB) return 2;
        return 1; // CGB-E
    }
    private static int indexToModel(int i) {
        if (i == 0) return NativeBridge.MODEL_DMG_B;
        if (i == 2) return NativeBridge.MODEL_AGB;
        return NativeBridge.MODEL_CGB_E;
    }
}
