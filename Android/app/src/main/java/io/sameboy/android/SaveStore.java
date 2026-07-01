package io.sameboy.android;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

final class SaveStore {
    private SaveStore() {}

    static File savFile(Context ctx, String romName) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) android.util.Log.e("SameBoy", "external files dir unavailable; battery saves may fail");
        File dir = new File(base, "saves");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, romName + ".sav");
    }

    static byte[] read(File f) {
        if (!f.exists()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) raf.length()];
            raf.readFully(b);
            return b;
        } catch (IOException e) { return null; }
    }

    static void write(File f, byte[] data) {
        if (data == null) return;
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(data); }
        catch (IOException e) { android.util.Log.e("SameBoy", "battery save failed: " + f, e); }
    }
}
