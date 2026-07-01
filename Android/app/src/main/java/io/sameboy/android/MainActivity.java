package io.sameboy.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;

public class MainActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setGravity(android.view.Gravity.CENTER);
        Button open = new Button(this);
        open.setText(R.string.open_rom);
        root.addView(open);
        setContentView(root);
    }
}
