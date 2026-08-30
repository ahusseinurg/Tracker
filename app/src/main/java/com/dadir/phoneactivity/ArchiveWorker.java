package com.dadir.phoneactivity;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ArchiveWorker extends Worker {
    private static final String PREFS = "media_library";
    private static final String UNIQUE_WORK = "phone-archive-auto-sync";
    private static final String UNIQUE_NOW = "phone-archive-active-sync";

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
        scheduleAlarm(context);
    }

    static void scheduleAlarm(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("auto_sync", true) || prefs.getString("backup_uri", null) == null) return;
        boolean daily = "daily_time".equals(prefs.getString("sync_schedule_mode", "interval"));
        long delay = daily ? delayUntilDailyTime(prefs)
                : TimeUnit.MINUTES.toMillis(Math.max(15, prefs.getLong("sync_interval_minutes", 15)));
        long trigger = System.currentTimeMillis() + delay;
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pending = alarmIntent(context);
        boolean exact = android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms();
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
        prefs.edit().putLong("next_sync_attempt", trigger).putBoolean("exact_alarm_allowed", exact).apply();
    }

    private static PendingIntent alarmIntent(Context context) {
        Intent intent = new Intent(context, BackupAlarmReceiver.class).setAction("com.dadir.phoneactivity.BACKUP_ALARM");
        return PendingIntent.getBroadcast(context, 404, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NOW, ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(ArchiveWorker.class).setConstraints(constraints).build());
    }

    static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK);
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NOW);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarms.cancel(alarmIntent(context));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("next_sync_attempt").apply();
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long started = System.currentTimeMillis();
        prefs.edit().putLong("last_sync_attempt", started).putString("last_sync_error", "")
                .putString("sync_phase", "Checking folder access").putInt("sync_progress_files", 0).apply();
        if (!prefs.getBoolean("auto_sync", true)) return stopped(prefs, "Automatic backup is turned off");
        String backupRaw = prefs.getString("backup_uri", null);
        if (backupRaw == null) return stopped(prefs, "No backup destination is connected");

        DocumentFile destination = DocumentFile.fromTreeUri(context, Uri.parse(backupRaw));
        if (destination == null || !destination.canWrite()) return stopped(prefs, "The backup destination cannot be written. Reconnect the Drive folder");
        Set<String> sources = new LinkedHashSet<>(prefs.getStringSet("folder_uris", new LinkedHashSet<>()));
        String legacy = prefs.getString("folder_uri", null);
        if (legacy != null) sources.add(legacy);
        if (sources.isEmpty()) return stopped(prefs, "No source folders are connected");
        SyncState state = new SyncState(started + TimeUnit.MINUTES.toMillis(12));
        prefs.edit().putString("sync_phase", "Scanning and copying files").apply();
        for (String raw : sources) {
            if (state.shouldStop(this)) break;
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(raw));
            if (root != null && root.canRead()) copyTree(root, root.getName() == null ? "" : root.getName(), destination, prefs, state);
            else state.failures++;
        }
        if (!state.timedOut) enforceRetention(destination, prefs.getInt("retention_days", 0));
        String message = state.timedOut ? "This run paused after 12 minutes and will continue at the next scheduled attempt"
                : state.failures == 0 ? "" : state.failures + " file(s) could not be copied";
        prefs.edit().putLong("last_sync", System.currentTimeMillis())
                .putLong("last_sync_duration_ms", System.currentTimeMillis() - started)
                .putInt("last_sync_copied", state.copied).putInt("last_sync_skipped", state.skipped)
                .putInt("last_sync_failures", state.failures).putBoolean("last_sync_partial", state.timedOut)
                .putString("sync_phase", "Idle").putInt("sync_progress_files", state.processed)
                .putString("last_sync_error", message).apply();
        return Result.success();
    }

    private Result stopped(SharedPreferences prefs, String message) {
        prefs.edit().putString("last_sync_error", message).putInt("last_sync_failures", 1).apply();
        return Result.failure();
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

    private void copyTree(DocumentFile folder, String path, DocumentFile destination,
                          SharedPreferences prefs, SyncState state) {
        if (state.shouldStop(this)) return;
        DocumentFile[] children;
        try { children = folder.listFiles(); } catch (Exception error) { state.failures++; return; }
        for (DocumentFile child : children) {
            if (state.shouldStop(this)) return;
            String name = child.getName() == null ? "Unnamed file" : child.getName();
            String childPath = path + "/" + name;
            if (child.isDirectory()) copyTree(child, childPath, destination, prefs, state);
            else if (child.isFile()) {
                String mime = child.getType() == null ? "application/octet-stream" : child.getType();
                String normalized = childPath.toLowerCase(Locale.US);
                boolean status = normalized.contains("/.statuses/") || normalized.contains("/status archive/") || normalized.contains("/statuses/");
                copyFile(child, name, mime, status, destination, prefs, state);
            }
        }
    }

    private void copyFile(DocumentFile source, String name, String mime, boolean status,
                          DocumentFile destination, SharedPreferences prefs, SyncState state) {
        state.processed++;
        if (state.processed % 20 == 0) prefs.edit().putInt("sync_progress_files", state.processed)
                .putString("sync_phase", "Scanning and copying files").apply();
        try {
            long start = prefs.getLong("backup_start_ms", 0);
            boolean includeExisting = prefs.getBoolean("backup_include_existing", false);
            long modified = source.lastModified();
            if (!includeExisting && (modified <= 0 || modified < start)) { state.skipped++; return; }
            String category = status ? "Statuses" : category(mime);
            if (!enabled(prefs, category)) { state.skipped++; return; }
            DocumentFile folder = destination.findFile(category);
            if (folder == null) folder = destination.createDirectory(category);
            if (folder == null || !folder.canWrite()) { state.failures++; return; }
            DocumentFile existing = folder.findFile(name);
            if (existing != null && existing.length() == source.length()) { state.skipped++; return; }
            String outputName = existing == null ? name : uniqueName(name, source.lastModified());
            DocumentFile output = folder.createFile(mime, outputName);
            if (output == null) { state.failures++; return; }
            try (InputStream in = getApplicationContext().getContentResolver().openInputStream(source.getUri());
                 OutputStream out = getApplicationContext().getContentResolver().openOutputStream(output.getUri())) {
                if (in == null || out == null) { state.failures++; return; }
                byte[] buffer = new byte[64 * 1024]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (isStopped()) return;
                    out.write(buffer, 0, count);
                }
            }
            state.copied++;
        } catch (Exception error) { state.failures++; }
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

    private static final class SyncState {
        final long deadline; int processed, copied, skipped, failures; boolean timedOut;
        SyncState(long deadline) { this.deadline = deadline; }
        boolean shouldStop(ArchiveWorker worker) {
            if (worker.isStopped()) return true;
            if (System.currentTimeMillis() >= deadline) { timedOut = true; return true; }
            return false;
        }
    }
}
