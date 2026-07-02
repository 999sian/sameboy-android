package io.sameboy.android;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Bind each GB input to a controller button: tap a row to arm, then press a button. */
public class GamepadRemapActivity extends AppCompatActivity {
    private GamepadMapper pad;
    private int capturing = -1;                 // GB key awaiting a keycode, or -1
    private final TextView[] rows = new TextView[GamepadMapper.KEYS];

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle(R.string.gamepad_buttons);
        pad = new GamepadMapper(this);
        int dp = (int) (getResources().getDisplayMetrics().density * 12);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp, dp, dp, dp);

        TextView hint = new TextView(this);
        hint.setText("Tap an input, then press a controller button.");
        hint.setPadding(0, 0, 0, dp);
        col.addView(hint);

        for (int i = 0; i < GamepadMapper.KEYS; i++) {
            final int gb = i;
            TextView row = new TextView(this);
            row.setPadding(0, dp, 0, dp);
            row.setOnClickListener(v -> { capturing = gb; renderAll(); });
            rows[i] = row;
            col.addView(row);
        }

        Button reset = new Button(this);
        reset.setText(R.string.reset_defaults);
        reset.setOnClickListener(v -> { pad.resetDefaults(); capturing = -1; renderAll(); });
        col.addView(reset);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        renderAll();
    }

    private void renderAll() {
        for (int i = 0; i < GamepadMapper.KEYS; i++) {
            String key = KeyEvent.keyCodeToString(pad.keycodeFor(i));
            rows[i].setText(GamepadMapper.GB_NAMES[i] + ":  "
                + (capturing == i ? "press a button…" : key));
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (capturing >= 0 && event.getAction() == KeyEvent.ACTION_DOWN
                && GamepadMapper.isGamepadKeycode(event.getKeyCode())) {
            pad.setBinding(capturing, event.getKeyCode());
            capturing = -1;
            renderAll();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
