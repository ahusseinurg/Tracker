package com.dadir.phoneactivity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;
import java.io.InputStream;
import java.io.OutputStream;

public class MediaLibraryActivity extends SecureActivity {
    private static final int PICK_FOLDER = 71;
    private static final String PREFS = "media_library";
    private static final String FOLDER_URI = "folder_uri";
    private static final String FOLDER_URIS = "folder_uris";
    private static final String BACKUP_URI = "backup_uri";
    private static final int PICK_BACKUP = 72;
    private final List<MediaItem> items = new ArrayList<>();
    private LinearLayout body;
    private String filter = "All";
    private String scanNotice = "";
    private final int navy = Color.rgb(16, 42, 67);
    private final int blue = Color.rgb(37, 99, 235);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildScreen());
        loadFolder();
    }

    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ModernUi.BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(20), dp(18), dp(18));
        ModernUi.fill(header,navy,0);
        TextView back = text("‹  Phone Activity", 15, Color.WHITE, true);
        back.setPadding(0, 0, 0, dp(10));
        back.setOnClickListener(v -> finish());
        header.addView(back);
        header.addView(text("Phone Media Archive", 26, Color.WHITE, true));
        header.addView(text("View approved phone and WhatsApp folders", 14, Color.rgb(210,225,240), false));
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(14), dp(14), dp(28));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private void loadFolder() {
        Set<String> saved = savedFolders();
        if (saved.isEmpty()) {
            showConnect();
            return;
        }
        scan(saved);
    }

    private Set<String> savedFolders() {
        Set<String> stored = getSharedPreferences(PREFS, MODE_PRIVATE).getStringSet(FOLDER_URIS, null);
        LinkedHashSet<String> result = stored == null ? new LinkedHashSet<>() : new LinkedHashSet<>(stored);
        String legacy = getSharedPreferences(PREFS, MODE_PRIVATE).getString(FOLDER_URI, null);
        if (legacy != null) result.add(legacy);
        return result;
    }

    private void showConnect() {
        body.removeAllViews();
        LinearLayout card = card();
        card.addView(text("Connect a phone folder", 20, navy, true));
        card.addView(text("Choose Pictures, Movies, Music, Documents, Downloads, or WhatsApp Media. Android will show exactly which folder you are authorizing.", 15, Color.DKGRAY, false));
        Button choose = button("Choose a folder");
        choose.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, PICK_FOLDER);
        });
        card.addView(choose, margin(0, 16, 0, 0));
        body.addView(card);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_BACKUP) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(BACKUP_URI, uri.toString()).apply();
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("auto_sync", true).apply();
                ArchiveWorker.schedule(this);
                ArchiveWorker.runNow(this);
                Toast.makeText(this, "Backup folder connected", Toast.LENGTH_SHORT).show();
                scan(savedFolders());
            } catch (SecurityException error) {
                Toast.makeText(this, "Backup folder access could not be saved", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != PICK_FOLDER) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Set<String> folders = savedFolders();
            folders.add(uri.toString());
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putStringSet(FOLDER_URIS, folders).remove(FOLDER_URI).apply();
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("auto_sync", true)
                    && (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("google_drive_connected",false)
                    || getSharedPreferences(PREFS, MODE_PRIVATE).getString(BACKUP_URI, null) != null)) {
                ArchiveWorker.schedule(this);
                ArchiveWorker.runNow(this);
            }
            scan(folders);
        } catch (SecurityException error) {
            Toast.makeText(this, "Folder access could not be saved", Toast.LENGTH_LONG).show();
        }
    }

    private void chooseBackupFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_BACKUP);
    }

    private void scan(Set<String> uris) {
        body.removeAllViews();
        ProgressBar progress = new ProgressBar(this);
        body.addView(progress, new LinearLayout.LayoutParams(-1, dp(56)));
        body.addView(text("Scanning media…", 16, navy, true));
        new Thread(() -> {
            List<MediaItem> found = new ArrayList<>();
            ScanState state = new ScanState(System.currentTimeMillis() + 20_000, 3000);
            int unavailable = 0;
            try {
                for (String raw : uris) {
                    if (state.shouldStop()) break;
                    try {
                        DocumentFile root = DocumentFile.fromTreeUri(this, Uri.parse(raw));
                        if (root != null && root.canRead()) walk(root, found, false,
                                root.getName() == null ? "" : root.getName(), state, 0);
                        else unavailable++;
                    } catch (Exception error) { unavailable++; }
                }
                Collections.sort(found, (a, b) -> Long.compare(b.modified, a.modified));
            } finally {
                int failedFolders = unavailable;
                runOnUiThread(() -> {
                    items.clear(); items.addAll(found);
                    if (state.truncated) scanNotice = "Showing the newest available files. The scan was limited to keep the app responsive.";
                    else if (failedFolders > 0) scanNotice = failedFolders + " connected folder(s) could not be read. Reconnect them if needed.";
                    else scanNotice = found.isEmpty() ? "No media files were found in the connected source folders." : "";
                    render();
                });
            }
        }).start();
    }

    private void walk(DocumentFile folder, List<MediaItem> output, boolean archived, String path, ScanState state, int depth) {
        if (state.shouldStop() || depth > 12) { state.truncated = true; return; }
        DocumentFile[] children;
        try { children = folder.listFiles(); } catch (Exception error) { return; }
        for (DocumentFile file : children) {
            if (state.shouldStop()) { state.truncated = true; return; }
            String childPath = path + "/" + (file.getName() == null ? "" : file.getName());
            if (file.isDirectory()) walk(file, output, archived, childPath, state, depth + 1);
            else if (file.isFile()) {
                output.add(new MediaItem(file.getName() == null ? "Unnamed file" : file.getName(),
                        file.getType(), file.getUri(), file.length(), file.lastModified(), archived,
                        isStatusPath(childPath)));
                state.count++;
            }
        }
    }

    private static final class ScanState {
        final long deadline; final int maximum; int count; boolean truncated;
        ScanState(long deadline, int maximum) { this.deadline = deadline; this.maximum = maximum; }
        boolean shouldStop() { return count >= maximum || System.currentTimeMillis() >= deadline; }
    }

    private boolean isStatusPath(String path) {
        String normalized = path.toLowerCase(Locale.US);
        return normalized.contains("/.statuses/") || normalized.endsWith("/.statuses")
                || normalized.contains("/status archive/") || normalized.contains("/statuses/");
    }

    private void render() {
        body.removeAllViews();
        LinearLayout tools = new LinearLayout(this);
        Button change = button("Add folder");
        change.setOnClickListener(v -> showConnect());
        Button refresh = button("Refresh");
        refresh.setOnClickListener(v -> { Set<String> saved = savedFolders(); if (!saved.isEmpty()) scan(saved); });
        tools.addView(change, new LinearLayout.LayoutParams(0, dp(48), 1));
        View gap = new View(this); tools.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        tools.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1));
        body.addView(tools);
        Button backup = button("Back up now");
        backup.setOnClickListener(v -> backupNow());
        body.addView(backup,new LinearLayout.LayoutParams(-1,dp(50)));
        boolean automatic = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("auto_sync", true);
        Button auto = button(automatic ? "Automatic remote updates: ON" : "Automatic remote updates: OFF");
        ModernUi.fill(auto,automatic ? ModernUi.GREEN : ModernUi.SLATE,14);
        auto.setOnClickListener(v -> {
            boolean next = !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("auto_sync", true);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("auto_sync", next).apply();
            if (next) { ArchiveWorker.schedule(this); ArchiveWorker.runNow(this); }
            else ArchiveWorker.cancel(this);
            render();
        });
        body.addView(auto, margin(0, 8, 0, 0));
        long lastSync = getSharedPreferences(PREFS, MODE_PRIVATE).getLong("last_sync", 0);
        int syncFailures = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("last_sync_failures", 0);
        String syncLine = lastSync == 0 ? "No automatic sync completed yet"
                : "Last automatic sync: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(lastSync)
                + (syncFailures > 0 ? "  •  " + syncFailures + " file(s) need retry" : "  •  Successful");
        body.addView(text(syncLine, 12, syncFailures > 0 ? Color.rgb(170,60,35) : Color.GRAY, false), margin(2, 6, 0, 0));
        body.addView(text(savedFolders().size() + " folders connected  •  " + items.size() + " files", 15, Color.DKGRAY, false), margin(2, 12, 0, 8));
        if (!scanNotice.isEmpty()) body.addView(text(scanNotice, 13, Color.rgb(150,70,30), false), margin(2, 0, 0, 10));

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"All", "Statuses", "Pictures", "Videos", "Audio", "Documents"};
        for (String name : names) {
            Button b = button(name);
            b.setTextSize(11); b.setAlpha(name.equals(filter) ? 1f : .52f);
            b.setOnClickListener(v -> { filter = name; render(); });
            filters.addView(b, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        body.addView(filters, margin(0, 0, 0, 14));

        int shown = 0;
        for (MediaItem item : items) {
            if (!filter.equals("All") && !filter.equals(item.category())) continue;
            body.addView(mediaRow(item), margin(0, 0, 0, 8));
            shown++;
            if (shown >= 500) break;
        }
        if (shown == 0) body.addView(text("No " + filter.toLowerCase(Locale.US) + " found.", 16, Color.DKGRAY, false));
        if (shown >= 500) body.addView(text("Showing the newest 500 matching files.", 13, Color.GRAY, false));

        Button disconnect = button("Disconnect all folders");
        ModernUi.fill(disconnect,Color.rgb(150,45,45),14);
        disconnect.setOnClickListener(v -> {
            for (String raw : savedFolders()) {
                try { getContentResolver().releasePersistableUriPermission(Uri.parse(raw), Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) { }
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(FOLDER_URIS).remove(FOLDER_URI).apply();
            items.clear(); showConnect();
        });
        body.addView(disconnect, margin(0, 22, 0, 0));
    }

    private View mediaRow(MediaItem item) {
        LinearLayout card = card();
        String symbol = item.status ? "STATUS" : item.category().equals("Pictures") ? "IMAGE" : item.category().equals("Videos") ? "VIDEO" : item.category().equals("Audio") ? "AUDIO" : "FILE";
        TextView type = text(symbol, 11, blue, true);
        card.addView(type);
        card.addView(text(item.name, 16, navy, true));
        if (item.archived) card.addView(text("PROTECTED COPY", 11, Color.rgb(21,128,61), true));
        String date = item.modified > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(item.modified) : "Date unavailable";
        card.addView(text(date + "  •  " + readableSize(item.size), 12, Color.GRAY, false));
        card.setOnClickListener(v -> open(item));
        return card;
    }

    private void open(MediaItem item) {
        Intent intent = new Intent(this, MediaViewerActivity.class);
        intent.putExtra("uri", item.uri.toString());
        intent.putExtra("mime", item.mime);
        intent.putExtra("name", item.name);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    }

    private void backupNow() {
        if(getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean("google_drive_connected",false)){
            ArchiveWorker.runNow(this);Toast.makeText(this,"Google Drive backup started",Toast.LENGTH_SHORT).show();return;
        }
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(BACKUP_URI, null);
        if (raw == null) { Toast.makeText(this,"Connect Google Drive in Settings first",Toast.LENGTH_LONG).show();startActivity(new Intent(this,SettingsActivity.class));return; }
        Toast.makeText(this, "Backup started", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            DocumentFile destination = DocumentFile.fromTreeUri(this, Uri.parse(raw));
            if (destination == null || !destination.canWrite()) {
                runOnUiThread(() -> Toast.makeText(this, "Backup folder is not writable", Toast.LENGTH_LONG).show());
                return;
            }
            int copied = 0, skipped = 0, failed = 0;
            for (MediaItem item : new ArrayList<>(items)) {
                if (item.archived) continue;
                try {
                    String folderName = item.status ? "Statuses" : item.category();
                    if (!manualTypeEnabled(folderName)) { skipped++; continue; }
                    DocumentFile targetFolder = destination.findFile(folderName);
                    if (targetFolder == null) targetFolder = destination.createDirectory(folderName);
                    if (targetFolder == null || !targetFolder.canWrite()) { failed++; continue; }
                    DocumentFile existing = targetFolder.findFile(item.name);
                    if (existing != null && existing.length() == item.size) { skipped++; continue; }
                    String outputName = existing == null ? item.name : uniqueName(item.name, System.currentTimeMillis());
                    DocumentFile output = targetFolder.createFile(item.mime, outputName);
                    if (output == null) { failed++; continue; }
                    try (InputStream in = getContentResolver().openInputStream(item.uri);
                         OutputStream out = getContentResolver().openOutputStream(output.getUri())) {
                        if (in == null || out == null) { failed++; continue; }
                        byte[] buffer = new byte[64 * 1024]; int count;
                        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                    copied++;
                } catch (Exception error) { failed++; }
            }
            int finalCopied = copied, finalSkipped = skipped, finalFailed = failed;
            runOnUiThread(() -> {
                Toast.makeText(this, finalCopied + " copied, " + finalSkipped + " already backed up, " + finalFailed + " failed", Toast.LENGTH_LONG).show();
                scan(savedFolders());
            });
        }).start();
    }

    private String uniqueName(String name, long marker) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + "-" + marker;
        return name.substring(0, dot) + "-" + marker + name.substring(dot);
    }

    private boolean manualTypeEnabled(String category) {
        if (category.equals("Statuses")) return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("type_statuses", true);
        if (category.equals("Pictures")) return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("type_pictures", true);
        if (category.equals("Videos")) return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("type_videos", true);
        if (category.equals("Audio")) return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("type_audio", true);
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("type_documents", true);
    }

    private LinearLayout card() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(16),dp(15),dp(16),dp(15)); ModernUi.outlined(l,Color.WHITE,ModernUi.BORDER,16); ModernUi.elevate(l,1); return l; }
    private Button button(String label) { Button b = new Button(this); b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setAllCaps(false); ModernUi.fill(b,blue,14); ModernUi.elevate(b,1); return b; }
    private TextView text(String value, int size, int color, boolean bold) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setLineSpacing(0,1.15f); if (bold) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t; }
    private ViewGroup.MarginLayoutParams margin(int l,int t,int r,int b) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private String readableSize(long bytes) { if (bytes < 1024) return bytes + " B"; if (bytes < 1024*1024) return (bytes/1024) + " KB"; return String.format(Locale.US,"%.1f MB",bytes/(1024d*1024d)); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
