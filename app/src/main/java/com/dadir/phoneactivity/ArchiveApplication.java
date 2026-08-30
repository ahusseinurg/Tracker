package com.dadir.phoneactivity;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public class ArchiveApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private int started;
    private boolean configurationChange;

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        android.content.SharedPreferences prefs = getSharedPreferences("media_library", MODE_PRIVATE);
        if (prefs.getBoolean("auto_sync", true) && prefs.getString("backup_uri", null) != null) {
            ArchiveWorker.schedule(this);
            long last = prefs.getLong("last_sync", 0);
            if (System.currentTimeMillis() - last > 15 * 60 * 1000L) ArchiveWorker.runNow(this);
        }
    }

    @Override public void onActivityStarted(Activity activity) {
        if (started == 0 && !configurationChange && !(activity instanceof PinActivity)) {
            LockStore.lock();
        }
        started++;
    }

    @Override public void onActivityStopped(Activity activity) {
        configurationChange = activity.isChangingConfigurations();
        started--;
        if (started == 0 && !configurationChange) LockStore.lock();
    }

    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityResumed(Activity a) { }
    @Override public void onActivityPaused(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    @Override public void onActivityDestroyed(Activity a) { }
}
