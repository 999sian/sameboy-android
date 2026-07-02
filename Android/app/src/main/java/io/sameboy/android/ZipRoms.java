package io.sameboy.android;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Minimal zip support: find and extract the first Game Boy ROM entry. */
final class ZipRoms {
    private ZipRoms() {}

    static boolean isRomName(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".gb") || n.endsWith(".gbc");
    }

    /** First entry (in zip order) whose name ends in .gb/.gbc, or null. */
    static String firstRomEntry(InputStream in) {
        try (ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.isDirectory() && isRomName(e.getName())) return e.getName();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Bytes of the named entry, or null if absent/unreadable. */
    static byte[] extract(InputStream in, String entryName) {
        try (ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().equals(entryName)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536]; int n;
                    while ((n = zin.read(buf)) > 0) out.write(buf, 0, n);
                    return out.toByteArray();
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
