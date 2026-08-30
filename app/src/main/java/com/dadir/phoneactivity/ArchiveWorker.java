package com.dadir.phoneactivity;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ArchiveWorker extends Worker {
    private static final String PREFS = "media_library";
    private static final String UNIQUE_WORK = "phone-archive-auto-sync";

    public ArchiveWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    static void schedule(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean daily = "daily_time".equals(prefs.getString("sync_schedule_mode", "interval"));
        long minutes = daily ? 24 * 60 : Math.max(15, prefs.getLong("sync_interval_minutes", 15));
        NetworkType network = prefs.getBoolean("wifi_only", false) ? NetworkType.UNMETERED : NetworkType.CONNECTED;
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(network).build();
        PeriodicWorkRequest.Builder builder = new PeriodicWorkRequest.Builder(ArchiveWorker.class, minutes, TimeUnit.MINUTES)
                .setConstraints(constraints);
        if (daily) builder.setInitialDelay(delayUntilDailyTime(prefs), TimeUnit.MILLISECONDS);
        PeriodicWorkRequest request = builder.build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    private static long delayUntilDailyTime(SharedPreferences prefs) {
        int hour = prefs.getInt("sync_daily_hour", 2);
        int minute = prefs.getInt("sync_daily_minute", 0);
        Calendar now = Calendar.getInstance();
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1);
        return next.getTimeInMillis() - now.getTimeInMillis();
    }

    static void runNow(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        NetworkType network = prefs.getBoolean("wifi_only", false) ? NetworkType.UNMETERED : NetworkType.CONNECTED;
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(network).build();
        WorkManager.getInstance(context).enqueue(new OneTimeWorkRequest.Builder(ArchiveWorker.class)
                .setConstraints(constraints).build());
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("auto_sync", true)) return Result.success();
        String backupRaw = prefs.getString("backup_uri", null);
        if (backupRaw == null) return Result.success();

        DocumentFile destination = DocumentFile.fromTreeUri(context, Uri.parse(backupRaw));
        if (destination == null || !destination.canWrite()) return Result.retry();
        Set<String> sources = new LinkedHashSet<>(prefs.getStringSet("folder_uris", new LinkedHashSet<>()));
        String legacy = prefs.getString("folder_uri", null);
        if (legacy != null) sources.add(legacy);
        List<SourceFile> files = new ArrayList<>();
        for (String raw : sources) {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(raw));
            if (root != null && root.canRead()) walk(root, root.getName() == null ? "" : root.getName(), files);
        }

        int failures = 0, copied = 0, skipped = 0;
        long started = System.currentTimeMillis();
        for (SourceFile source : files) {
            try {
                String category = source.status ? "Statuses" : category(source.mime);
                if (!enabled(prefs, category)) { skipped++; continue; }
                DocumentFile folder = destination.findFile(category);
                if (folder == null) folder = destination.createDirectory(category);
                if (folder == null || !folder.canWrite()) { failures++; continue; }
                DocumentFile existing = folder.findFile(source.name);
                if (existing != null && existing.length() == source.size) { skipped++; continue; }
                String name = existing == null ? source.name : uniqueName(source.name, source.modified);
                DocumentFile output = folder.createFile(source.mime, name);
                if (output == null) { failures++; continue; }
                try (InputStream in = context.getContentResolver().openInputStream(source.uri);
                     OutputStream out = context.getContentResolver().openOutputStream(output.getUri())) {
                    if (in == null || out == null) { failures++; continue; }
                    byte[] buffer = new byte[64 * 1024]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                copied++;
            } catch (Exception error) { failures++; }
        }
        enforceRetention(destination, prefs.getInt("retention_days", 0));
        prefs.edit().putLong("last_sync", System.currentTimeMillis())
                .putLong("last_sync_duration_ms", System.currentTimeMillis() - started)
                .putInt("last_sync_copied", copied).putInt("last_sync_skipped", skipped)
                .putInt("last_sync_failures", failures).apply();
        return failures == 0 ? Result.success() : Result.retry();
    }

    private static boolean enabled(SharedPreferences prefs, String category) {
        if (category.equals("Statuses")) return prefs.getBoolean("type_statuses", true);
        if (category.equals("Pictures")) return prefs.getBoolean("type_pictures", true);
        if (category.equals("Videos")) return prefs.getBoolean("type_videos", true);
        if (category.equals("Audio")) return prefs.getBoolean("type_audio", true);
        return prefs.getBoolean("type_documents", true);
    }

    private static void enforceRetention(DocumentFile destination, int days) {
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - days * 86_400_000L;
        try {
            for (DocumentFile folder : destination.listFiles()) {
                if (!folder.isDirectory()) continue;
                for (DocumentFile file : folder.listFiles()) {
                    if (file.isFile() && file.lastModified() > 0 && file.lastModified() < cutoff) file.delete();
                }
            }
        } catch (Exception ignored) { }
    }

    private void walk(DocumentFile folder, String path, List<SourceFile> files) {
        DocumentFile[] children;
        try { children = folder.listFiles(); } catch (Exception error) { return; }
        for (DocumentFile child : children) {
            String name = child.getName() == null ? "Unnamed file" : child.getName();
            String childPath = path + "/" + name;
            if (child.isDirectory()) walk(child, childPath, files);
            else if (child.isFile()) {
                String mime = child.getType() == null ? "application/octet-stream" : child.getType();
                String normalized = childPath.toLowerCase(Locale.US);
                boolean status = normalized.contains("/.statuses/") || normalized.contains("/status archive/") || normalized.contains("/statuses/");
                files.add(new SourceFile(name, mime, child.getUri(), child.length(), child.lastModified(), status));
            }
        }
    }

    private static String category(String mime) {
        if (mime.startsWith("image/")) return "Pictures";
        if (mime.startsWith("video/")) return "Videos";
        if (mime.startsWith("audio/")) return "Audio";
        return "Documents";
    }

    private static String uniqueName(String name, long marker) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + "-" + marker;
        return name.substring(0, dot) + "-" + marker + name.substring(dot);
    }

    private static final class SourceFile {
        final String name, mime; final Uri uri; final long size, modified; final boolean status;
        SourceFile(String name, String mime, Uri uri, long size, long modified, boolean status) {
            this.name=name; this.mime=mime; this.uri=uri; this.size=size; this.modified=modified; this.status=status;
        }
    }
}
