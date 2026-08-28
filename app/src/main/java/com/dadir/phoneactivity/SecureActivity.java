package com.dadir.phoneactivity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

public abstract class SecureActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (!LockStore.isUnlocked()) {
            startActivity(new Intent(this, PinActivity.class));
            finish();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!LockStore.isUnlocked()) {
            startActivity(new Intent(this, PinActivity.class));
            finish();
        }
    }
}
