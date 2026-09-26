package org.dadirsons.budgettracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BackupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent != null ? intent.getAction() : null)) {
            MainActivity.scheduleBackgroundBackup(context.getApplicationContext(), 15 * 60 * 1000L);
            return;
        }
        final PendingResult result = goAsync();
        new Thread(() -> {
            try { MainActivity.performBackup(context.getApplicationContext()); }
            finally { result.finish(); }
        }).start();
    }
}
