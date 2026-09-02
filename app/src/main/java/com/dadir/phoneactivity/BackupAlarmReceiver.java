package com.dadir.phoneactivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BackupAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            ArchiveWorker.schedule(context);
            return;
        }
        ArchiveWorker.runNow(context);
        ArchiveWorker.scheduleAlarm(context);
    }
}
