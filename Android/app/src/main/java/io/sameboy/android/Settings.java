package io.sameboy.android;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent user settings + one-shot apply to a running session.
 *  Sliders store ints; the Core mapping mirrors SameBoy's SDL frontend. */
final class Settings {
    private static final String PREFS = "sameboy_settings";

    // keys
    private static final String K_MODEL = "model";                 // GB_model_t int
    private static final String K_REWIND = "rewind_seconds";       // int seconds
    private static final String K_RTC = "rtc_mode";                // 0 sync, 1 accurate
    private static final String K_TURBO_CAP = "turbo_cap_quarters";// int, /4 => multiplier, 0=uncapped
    private static final String K_COLOR = "color_correction";      // 0..6
    private static final String K_LIGHT = "light_slider";          // 0..20, (v-10)/10
    private static final String K_BORDER = "border_mode";          // 0 SGB,1 Never,2 Always
    private static final String K_VOLUME = "volume_pct";           // 0..100
    private static final String K_HIGHPASS = "highpass";           // 0..2
    private static final String K_INTERFERENCE = "interference_pct"; // 0..100
    private static final String K_OPACITY = "button_opacity_pct";  // 0..100
    private static final String K_HAPTICS = "haptics";             // bool

    private final SharedPreferences p;

    Settings(Context ctx) { p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    // --- typed accessors (default = SameBoy/SDL defaults) ---
    int model()             { return p.getInt(K_MODEL, NativeBridge.MODEL_CGB_E); }
    void setModel(int v)    { p.edit().putInt(K_MODEL, v).apply(); }
    int rewindSeconds()     { return p.getInt(K_REWIND, 120); }
    void setRewindSeconds(int v){ p.edit().putInt(K_REWIND, v).apply(); }
    int rtcMode()           { return p.getInt(K_RTC, 0); }
    void setRtcMode(int v)  { p.edit().putInt(K_RTC, v).apply(); }
    int turboCapQuarters()  { return p.getInt(K_TURBO_CAP, 0); }
    void setTurboCapQuarters(int v){ p.edit().putInt(K_TURBO_CAP, v).apply(); }
    int colorCorrection()   { return p.getInt(K_COLOR, 2); }
    void setColorCorrection(int v){ p.edit().putInt(K_COLOR, v).apply(); }
    int lightSlider()       { return p.getInt(K_LIGHT, 10); }
    void setLightSlider(int v){ p.edit().putInt(K_LIGHT, v).apply(); }
    int borderMode()        { return p.getInt(K_BORDER, 0); }
    void setBorderMode(int v){ p.edit().putInt(K_BORDER, v).apply(); }
    int volumePct()         { return p.getInt(K_VOLUME, 100); }
    void setVolumePct(int v){ p.edit().putInt(K_VOLUME, v).apply(); }
    int highpass()          { return p.getInt(K_HIGHPASS, 1); }
    void setHighpass(int v) { p.edit().putInt(K_HIGHPASS, v).apply(); }
    int interferencePct()   { return p.getInt(K_INTERFERENCE, 0); }
    void setInterferencePct(int v){ p.edit().putInt(K_INTERFERENCE, v).apply(); }
    int buttonOpacityPct()  { return p.getInt(K_OPACITY, 60); }
    void setButtonOpacityPct(int v){ p.edit().putInt(K_OPACITY, v).apply(); }
    boolean haptics()       { return p.getBoolean(K_HAPTICS, true); }
    void setHaptics(boolean v){ p.edit().putBoolean(K_HAPTICS, v).apply(); }

    /** Model to boot the next launch — validated against the three supported models
     *  (a corrupt/tampered prefs value would otherwise reach GB_init unclamped). */
    int modelForLaunch() {
        int m = model();
        return (m == NativeBridge.MODEL_DMG_B || m == NativeBridge.MODEL_AGB) ? m : NativeBridge.MODEL_CGB_E;
    }
    /** On-screen control alpha 0..1. */
    float buttonOpacity() { return buttonOpacityPct() / 100f; }

    /** Push every Core-backed setting + volume to a running session. */
    void apply(long ctx) {
        if (ctx == 0) return;
        NativeBridge.nativeApplySettings(ctx,
            colorCorrection(),
            (lightSlider() - 10) / 10.0,
            borderMode(),
            highpass(),
            rtcMode(),
            rewindSeconds(),
            turboCapQuarters() / 4.0,
            interferencePct() / 100.0);
        NativeBridge.nativeSetVolume(ctx, volumePct() * 256 / 100);
    }
}
