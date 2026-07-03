package io.sameboy.android;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;

/** Thin shell: all UI lives in SettingsUi (Compose, Cupertino look).
 *  Writes SharedPreferences via Settings; EmulatorActivity applies on resume. */
public class SettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        SettingsUi.bind(this, new Settings(this));
    }
}
