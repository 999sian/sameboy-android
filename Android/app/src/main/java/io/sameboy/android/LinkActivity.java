package io.sameboy.android;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Link-cable connect screen: Host (listen on 1989) or Join (peer IP), with a live status
 *  line polling NativeBridge.nativeLinkStatus. TCP over the local network. */
public final class LinkActivity extends AppCompatActivity {
    public static final String EXTRA_CTX = "io.sameboy.ctx";
    private static final int PORT = 1989;
    private long ctx;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String[] names = { "Idle", "Listening", "Connecting", "Connected", "Error" };
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (ctx != 0) {
                int st = NativeBridge.nativeLinkStatus(ctx);
                status.setText("Status: " + names[st >= 0 && st < names.length ? st : 0]);
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) { finish(); return; }          // process-death: stale ctx
        ctx = getIntent().getLongExtra(EXTRA_CTX, 0);
        if (ctx == 0) { finish(); return; }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView ip = new TextView(this);
        ip.setText("This device: " + localIp() + "  (port " + PORT + ")");
        root.addView(ip);

        Button host = new Button(this); host.setText(R.string.link_host);
        root.addView(host);

        final EditText peer = new EditText(this);
        peer.setInputType(InputType.TYPE_CLASS_TEXT);
        peer.setHint(R.string.link_hint_join);
        root.addView(peer);
        Button join = new Button(this); join.setText(R.string.link_join);
        root.addView(join);

        Button disc = new Button(this); disc.setText(R.string.link_disconnect);
        root.addView(disc);

        status = new TextView(this);
        status.setPadding(0, 32, 0, 0);
        root.addView(status);
        setContentView(root);

        host.setOnClickListener(v -> NativeBridge.nativeLinkListen(ctx, PORT));
        join.setOnClickListener(v -> {
            String h = peer.getText().toString().trim();
            if (!h.isEmpty()) NativeBridge.nativeLinkConnect(ctx, h, PORT);
        });
        disc.setOnClickListener(v -> NativeBridge.nativeLinkDisconnect(ctx));
    }

    @Override protected void onResume() { super.onResume(); handler.post(poll); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(poll); }

    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "?";
    }
}
