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
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(ArchiveWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(constraints).build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    static void runNow(Context context) {
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
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

        int failures = 0;
        for (SourceFile source : files) {
            try {
                String category = source.status ? "Statuses" : category(source.mime);
                DocumentFile folder = destination.findFile(category);
                if (folder == null) folder = destination.createDirectory(category);
                if (folder == null || !folder.canWrite()) { failures++; continue; }
                DocumentFile existing = folder.findFile(source.name);
                if (existing != null && existing.length() == source.size) continue;
                String name = existing == null ? source.name : uniqueName(source.name, source.modified);
                DocumentFile output = folder.createFile(source.mime, name);
                if (output == null) { failures++; continue; }
                try (InputStream in = context.getContentResolver().openInputStream(source.uri);
                     OutputStream out = context.getContentResolver().openOutputStream(output.getUri())) {
                    if (in == null || out == null) { failures++; continue; }
                    byte[] buffer = new byte[64 * 1024]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
            } catch (Exception error) { failures++; }
        }
        prefs.edit().putLong("last_sync", System.currentTimeMillis()).putInt("last_sync_failures", failures).apply();
        return failures == 0 ? Result.success() : Result.retry();
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
