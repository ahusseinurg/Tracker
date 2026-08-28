package com.dadir.phoneactivity;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class UsageRepository {
    static final class AppRow {
        final String label;
        final String packageName;
        final long foregroundMs;
        final long lastUsed;

        AppRow(String label, String packageName, long foregroundMs, long lastUsed) {
            this.label = label;
            this.packageName = packageName;
            this.foregroundMs = foregroundMs;
            this.lastUsed = lastUsed;
        }
    }

    static final class DeviceSummary {
        int screenOns;
        int unlocks;
    }

    private final Context context;
    private final UsageStatsManager manager;

    UsageRepository(Context context) {
        this.context = context;
        this.manager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    boolean hasAccess() {
        AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    List<AppRow> apps(long start, long end) {
        Map<String, UsageStats> stats = manager.queryAndAggregateUsageStats(start, end);
        PackageManager pm = context.getPackageManager();
        List<AppRow> rows = new ArrayList<>();
        for (UsageStats item : stats.values()) {
            if (item.getTotalTimeInForeground() <= 0) continue;
            String label = item.getPackageName();
            try {
                ApplicationInfo info = pm.getApplicationInfo(item.getPackageName(), 0);
                label = pm.getApplicationLabel(info).toString();
            } catch (PackageManager.NameNotFoundException ignored) { }
            rows.add(new AppRow(label, item.getPackageName(),
                    item.getTotalTimeInForeground(), item.getLastTimeUsed()));
        }
        Collections.sort(rows, (a, b) -> Long.compare(b.foregroundMs, a.foregroundMs));
        return rows;
    }

    DeviceSummary deviceSummary(long start, long end) {
        DeviceSummary summary = new DeviceSummary();
        UsageEvents events = manager.queryEvents(start, end);
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.SCREEN_INTERACTIVE) summary.screenOns++;
            if (event.getEventType() == UsageEvents.Event.KEYGUARD_HIDDEN) summary.unlocks++;
        }
        return summary;
    }

    static String duration(long millis) {
        long minutes = millis / 60_000;
        long hours = minutes / 60;
        minutes %= 60;
        return hours > 0 ? String.format(Locale.US, "%dh %02dm", hours, minutes)
                : String.format(Locale.US, "%dm", minutes);
    }

    static String lastUsed(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(millis);
    }
}
