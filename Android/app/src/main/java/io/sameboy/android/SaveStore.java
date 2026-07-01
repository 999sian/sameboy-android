package io.sameboy.android;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

final class SaveStore {
    private SaveStore() {}

    /** External app storage when mounted, else internal — never null (fixes M1 NPE). */
    private static File subDir(Context ctx, String name) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, name);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static File savFile(Context ctx, String romName) {
        return new File(subDir(ctx, "saves"), romName + ".sav");
    }

    /** Save-state slot file: states/<rom>.s<slot> (mirrors iOS GBROMManager). */
    static File stateFile(Context ctx, String romName, int slot) {
        return new File(subDir(ctx, "states"), romName + ".s" + slot);
    }

    /** Slot thumbnail PNG, sibling of the state file. */
    static File stateThumb(Context ctx, String romName, int slot) {
        return new File(subDir(ctx, "states"), romName + ".s" + slot + ".png");
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
        catch (IOException e) { android.util.Log.e("SameBoy", "write failed: " + f, e); }
    }
}
