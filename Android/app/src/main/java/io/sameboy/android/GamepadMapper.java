package io.sameboy.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** Hardware-gamepad → GB-key mapping (keycode table + prefs) and axis→d-pad translation. */
final class GamepadMapper {
    // GB key indices (match NativeBridge)
    static final int RIGHT = 0, LEFT = 1, UP = 2, DOWN = 3, A = 4, B = 5, SELECT = 6, START = 7;
    static final int KEYS = 8;
    static final String[] GB_NAMES = { "Right", "Left", "Up", "Down", "A", "B", "Select", "Start" };

    private static final int[] DEFAULTS = {
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_UP,    KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_BUTTON_A,   KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
    };

    private final SharedPreferences p;
    private final int[] keycodeFor = new int[KEYS];

    GamepadMapper(Context ctx) {
        p = ctx.getApplicationContext().getSharedPreferences("sameboy_gamepad", Context.MODE_PRIVATE);
        load();
    }

    /** (Re)load bindings from prefs — call after they may have changed in another instance. */
    void load() {
        for (int i = 0; i < KEYS; i++) keycodeFor[i] = p.getInt("gp_" + i, DEFAULTS[i]);
    }

    /** GB key (0..7) bound to this keycode, or -1. */
    int gbKeyForKeycode(int keycode) {
        for (int i = 0; i < KEYS; i++) if (keycodeFor[i] == keycode) return i;
        return -1;
    }

    int keycodeFor(int gbKey) { return keycodeFor[gbKey]; }

    void setBinding(int gbKey, int keycode) {
        // A keycode maps to one GB key: clear any other key currently holding it.
        SharedPreferences.Editor e = p.edit();
        for (int i = 0; i < KEYS; i++) {
            if (i != gbKey && keycodeFor[i] == keycode) { keycodeFor[i] = -1; e.putInt("gp_" + i, -1); }
        }
        keycodeFor[gbKey] = keycode;
        e.putInt("gp_" + gbKey, keycode).apply();
    }

    void resetDefaults() {
        SharedPreferences.Editor e = p.edit();
        for (int i = 0; i < KEYS; i++) { keycodeFor[i] = DEFAULTS[i]; e.putInt("gp_" + i, DEFAULTS[i]); }
        e.apply();
    }

    /** True for a gamepad/joystick keycode we might bind (used to arm capture + ignore stray keys). */
    static boolean isGamepadKeycode(int keycode) {
        return KeyEvent.isGamepadButton(keycode)
            || keycode == KeyEvent.KEYCODE_DPAD_UP || keycode == KeyEvent.KEYCODE_DPAD_DOWN
            || keycode == KeyEvent.KEYCODE_DPAD_LEFT || keycode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    static boolean anyGamepadConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null) continue;
            int src = d.getSources();
            if ((src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        }
        return false;
    }

    /** D-pad edges from a joystick MotionEvent (hat + left stick), deadzone 0.5.
     *  Returns {right,left,up,down} booleans. */
    static boolean[] axisDpad(MotionEvent e) {
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float sx = e.getAxisValue(MotionEvent.AXIS_X);
        float sy = e.getAxisValue(MotionEvent.AXIS_Y);
        float x = Math.abs(hx) > Math.abs(sx) ? hx : sx;
        float y = Math.abs(hy) > Math.abs(sy) ? hy : sy;
        return new boolean[]{ x > 0.5f, x < -0.5f, y < -0.5f, y > 0.5f };  // right,left,up,down
    }
}
