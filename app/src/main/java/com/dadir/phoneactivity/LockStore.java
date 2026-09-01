package com.dadir.phoneactivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class LockStore {
    private static final String PREFS = "app_lock";
    private static volatile boolean unlocked;

    static boolean hasPin(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("hash");
    }

    static boolean setPin(Context context, String pin) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = derive(pin, salt);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString("hash", Base64.encodeToString(hash, Base64.NO_WRAP)).apply();
            unlocked = true;
            return true;
        } catch (Exception error) { return false; }
    }

    static boolean verify(Context context, String pin) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            byte[] salt = Base64.decode(prefs.getString("salt", ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(prefs.getString("hash", ""), Base64.NO_WRAP);
            boolean valid = Arrays.equals(expected, derive(pin, salt));
            if (valid) unlocked = true;
            return valid;
        } catch (Exception error) { return false; }
    }

    private static byte[] derive(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 120_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    static boolean isUnlocked() { return unlocked; }
    static void unlock() { unlocked = true; }
    static void lock() { unlocked = false; }
}
