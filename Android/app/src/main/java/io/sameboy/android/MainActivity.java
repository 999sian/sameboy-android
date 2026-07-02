package io.sameboy.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_TREE = 1, REQ_FILE = 2;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Library library;
    private GridView grid;
    private LibraryAdapter adapter;
    private TextView empty;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        library = new Library(this);
        library.load();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button importFolder = new Button(this);
        importFolder.setText(R.string.import_folder);
        importFolder.setOnClickListener(v -> pickTree());
        Button openFile = new Button(this);
        openFile.setText(R.string.open_rom);
        openFile.setOnClickListener(v -> pickFile());
        bar.addView(importFolder);
        bar.addView(openFile);
        root.addView(bar);

        empty = new TextView(this);
        empty.setText(R.string.library_empty);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, 64, 0, 0);
        root.addView(empty);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        int cell = (int) (getResources().getDisplayMetrics().density * 150);
        grid.setColumnWidth(cell);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        adapter = new LibraryAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, view, pos, id) -> launch(adapter.items.get(pos)));
        grid.setOnItemLongClickListener((p, view, pos, id) -> { showContext(adapter.items.get(pos)); return true; });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        library.load();   // pick up a background scan's save / another instance's changes
        refresh();
    }

    private void refresh() {
        adapter.items.clear();
        adapter.items.addAll(library.listSorted());
        adapter.notifyDataSetChanged();
        boolean e = adapter.items.isEmpty();
        empty.setVisibility(e ? View.VISIBLE : View.GONE);
        grid.setVisibility(e ? View.GONE : View.VISIBLE);
    }

    private void pickTree() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }
    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_FILE);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int grant = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try { getContentResolver().takePersistableUriPermission(uri, grant); } catch (Exception ignored) {}

        if (req == REQ_TREE) {
            Toast.makeText(this, R.string.scanning, Toast.LENGTH_SHORT).show();
            io.execute(() -> {
                int[] added = {0};
                RomScanner.scanTree(this, uri, entry ->
                    ui.post(() -> { if (library.add(entry)) added[0]++; }));
                ui.post(() -> {
                    library.save();
                    refresh();
                    Toast.makeText(this, getString(R.string.added_n, added[0]), Toast.LENGTH_SHORT).show();
                });
            });
        } else { // REQ_FILE
            io.execute(() -> {
                String name = queryName(uri);   // SAF query off the main thread (ANR-safe)
                boolean[] got = {false};
                RomScanner.handleFile(getContentResolver(), uri, name, entry -> {
                    got[0] = true;
                    ui.post(() -> { library.add(entry); library.save(); refresh(); launch(entry); });
                });
                if (!got[0]) ui.post(() -> Toast.makeText(this, R.string.not_a_rom, Toast.LENGTH_SHORT).show());
            });
        }
    }

    private void launch(LibraryEntry entry) {
        library.touch(entry.crc32);
        library.save();
        Intent i = new Intent(this, EmulatorActivity.class);
        i.setData(Uri.parse(entry.uri));
        if (entry.zipEntry != null) i.putExtra(EmulatorActivity.EXTRA_ZIP_ENTRY, entry.zipEntry);
        i.putExtra(EmulatorActivity.EXTRA_ROM_KEY, entry.crc32);
        startActivity(i);
    }

    private void showContext(LibraryEntry entry) {
        String fav = entry.favorite ? getString(R.string.unfavorite) : getString(R.string.favorite);
        String[] actions = { getString(R.string.play), fav, getString(R.string.remove) };
        new AlertDialog.Builder(this)
            .setTitle(entry.label())
            .setItems(actions, (d, which) -> {
                switch (which) {
                    case 0: launch(entry); break;
                    case 1: library.setFavorite(entry.crc32, !entry.favorite); library.save(); refresh(); break;
                    case 2: library.remove(entry.crc32); library.save(); refresh(); break;
                }
            }).show();
    }

    private String queryName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{ OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {}
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "rom" : name;
    }

    private final class LibraryAdapter extends BaseAdapter {
        final List<LibraryEntry> items = new ArrayList<>();
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout cell;
            if (convert instanceof LinearLayout) {
                cell = (LinearLayout) convert;
            } else {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                int pad = (int) (getResources().getDisplayMetrics().density * 8);
                cell.setPadding(pad, pad, pad, pad);
                TextView title = new TextView(MainActivity.this); title.setId(1);
                TextView sub = new TextView(MainActivity.this); sub.setId(2);
                cell.addView(title);
                cell.addView(sub);
            }
            LibraryEntry e = items.get(pos);
            ((TextView) cell.findViewById(1)).setText((e.favorite ? "\u2605 " : "") + e.label());
            ((TextView) cell.findViewById(2)).setText(e.lastPlayed == 0
                ? getString(R.string.never)
                : DateUtils.getRelativeTimeSpanString(e.lastPlayed).toString());
            return cell;
        }
    }
}
