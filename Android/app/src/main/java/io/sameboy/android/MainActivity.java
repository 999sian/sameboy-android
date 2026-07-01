package io.sameboy.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setGravity(Gravity.CENTER);
        Button open = new Button(this);
        open.setText(R.string.open_rom);
        open.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_OPEN);
        });
        root.addView(open);
        setContentView(root);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OPEN && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            Intent i = new Intent(this, EmulatorActivity.class);
            i.setData(uri);
            startActivity(i);
        }
    }
}
