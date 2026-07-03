package io.sameboy.android;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

/** Link-cable connect screen: Host (listen on 1989) or Join (peer IP), with a live status
 *  line polling NativeBridge.nativeLinkStatus. TCP over the local network. */
public final class LinkActivity extends AppCompatActivity {
    public static final String EXTRA_CTX = "io.sameboy.ctx";
    private static final int PORT = 1989;
    private long ctx;
    private LinkUi.Model model;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final String[] names = { "Idle", "Listening", "Connecting", "Connected", "Error" };
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (ctx != 0) {
                int st = NativeBridge.nativeLinkStatus(ctx);
                model.setStatus(names[st >= 0 && st < names.length ? st : 0]);
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) { finish(); return; }          // process-death: stale ctx
        ctx = getIntent().getLongExtra(EXTRA_CTX, 0);
        if (ctx == 0) { finish(); return; }

        model = LinkUi.bind(this,
            "This device: " + localIp() + "  (port " + PORT + ")",
            new LinkUi.Callbacks() {
                @Override public void onHost() { NativeBridge.nativeLinkListen(ctx, PORT); }
                @Override public void onJoin(String ip) { NativeBridge.nativeLinkConnect(ctx, ip, PORT); }
                @Override public void onDisconnect() { NativeBridge.nativeLinkDisconnect(ctx); }
                @Override public void onBack() { finish(); }
            });
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
