package org.dadirsons.budgettracker;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.print.PrintAttributes;
import android.print.PrintManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class MainActivity extends Activity {
    private static final int PICK_FILE = 201;
    private static final int SAVE_FILE = 202;
    private static final int PICK_BACKUP_FOLDER = 203;
    private static final String PREFS = "maping_backup";
    private static final String KEY_URI = "backup_tree_uri";
    private static final String KEY_PAYLOAD = "backup_payload";
    private static final String KEY_LAST_OK = "last_success_ms";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_RETRY = "retry_count";
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingContent, pendingName, pendingMime;

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                installBackupHooks();
            }
        });
        webView.addJavascriptInterface(new AppBridge(), "AndroidApp");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, PICK_FILE);
                } catch (Exception e) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(intent, PICK_FILE);
                }
                return true;
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
        scheduleBackup(6 * 60 * 60 * 1000L);
    }

    private void installBackupHooks() {
        String js = "(function(){try{" +
                "function sync(){try{var x=localStorage.getItem('myMoneyV3');if(x&&window.AndroidApp&&AndroidApp.persistBackupPayload)AndroidApp.persistBackupPayload(x)}catch(e){}}" +
                "var oldSave=window.save;if(oldSave&&!window.__mapingSaveWrapped){window.__mapingSaveWrapped=true;window.save=function(){var r=oldSave.apply(this,arguments);sync();return r}}" +
                "var b=document.getElementById('backupBtn');if(b&&!b.__mapingHooked){b.__mapingHooked=true;b.onclick=function(){if(window.AndroidApp&&AndroidApp.backupNow)AndroidApp.backupNow();else if(oldSave)oldSave()}}" +
                "var card=b&&b.closest('.card');if(card&&!document.getElementById('mapingBackupStatus')){var box=document.createElement('div');box.id='mapingBackupStatus';box.style='margin:12px 0;padding:12px;border-radius:14px;background:#eef6f1;font-size:14px';box.innerHTML='<b>Automatic backup</b><div id=mapingBackupText>Checking backup status...</div><button id=mapingBackupFolder class=secondary style=\"margin-top:8px;width:100%\">Choose / change Google Drive folder</button>';card.insertBefore(box,b.parentElement);document.getElementById('mapingBackupFolder').onclick=function(){AndroidApp.chooseBackupFolder()}}" +
                "function status(){try{var s=JSON.parse(AndroidApp.getBackupStatus());var t=document.getElementById('mapingBackupText');if(!t)return;if(!s.configured){t.textContent='Not configured - choose a Google Drive folder once.';return}if(s.error){t.textContent='Backup problem. Retrying automatically. '+s.error;return}t.textContent=s.lastSuccess?'Last verified backup: '+new Date(s.lastSuccess).toLocaleString():'Waiting for first verified backup.'}catch(e){}}" +
                "sync();status();if(!window.__mapingBackupTimer){window.__mapingBackupTimer=setInterval(function(){sync();status()},300000)}" +
                "window.mapingBackupFolderConfigured=function(){sync();status()};" +
                "}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    public class AppBridge {
        @JavascriptInterface public void persistBackupPayload(String content) {
            if (content == null || content.length() == 0) return;
            prefs().edit().putString(KEY_PAYLOAD, content).apply();
            scheduleBackgroundBackup(MainActivity.this, 30 * 60 * 1000L);
        }

        @JavascriptInterface public String getBackupStatus() {
            long ok = prefs().getLong(KEY_LAST_OK, 0);
            String uri = prefs().getString(KEY_URI, "");
            String error = prefs().getString(KEY_LAST_ERROR, "");
            int retry = prefs().getInt(KEY_RETRY, 0);
            return "{\"configured\":" + (!uri.isEmpty()) + ",\"lastSuccess\":" + ok + ",\"error\":" + json(error) + ",\"retry\":" + retry + "}";
        }

        @JavascriptInterface public void chooseBackupFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                startActivityForResult(i, PICK_BACKUP_FOLDER);
            });
        }

        @JavascriptInterface public void backupNow() {
            if (prefs().getString(KEY_URI, "").isEmpty()) chooseBackupFolder();
            else performBackup();
        }

        @JavascriptInterface public void saveFile(String content, String name, String mime) {
            pendingContent = content; pendingName = name; pendingMime = mime;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(pendingMime == null ? "text/plain" : pendingMime);
                i.putExtra(Intent.EXTRA_TITLE, pendingName == null ? "My-Money-Export.txt" : pendingName);
                startActivityForResult(i, SAVE_FILE);
            });
        }
        @JavascriptInterface public void printReport() {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager)getSystemService(PRINT_SERVICE);
                pm.print("My Money Report", webView.createPrintDocumentAdapter("My Money Report"), new PrintAttributes.Builder().build());
            });
        }
    }

    private static String json(String s) { return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")) + "\""; }

    private void scheduleBackup(long delayMs) { scheduleBackgroundBackup(this, delayMs); }

    public static void scheduleBackgroundBackup(Context context, long delayMs) {
        AlarmManager am = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(context, BackupReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(context, 9001, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long when = System.currentTimeMillis() + Math.max(15 * 60 * 1000L, delayMs);
        if (android.os.Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        else am.set(AlarmManager.RTC_WAKEUP, when, pi);
    }

    private void performBackup() { performBackup(this); }

    public static void performBackup(Context context) {
        SharedPreferences p = prefs(context);
        String tree = p.getString(KEY_URI, "");
        String payload = p.getString(KEY_PAYLOAD, "");
        if (tree.isEmpty() || payload.isEmpty()) return;
        try {
            Uri treeUri = Uri.parse(tree);
            Uri fileUri = findOrCreateFile(context, treeUri, "My-Money-Auto-Backup.json", "application/json");
            if (fileUri == null) throw new Exception("Could not create backup file");
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = context.getContentResolver().openOutputStream(fileUri, "wt")) {
                if (out == null) throw new Exception("Could not open backup destination");
                out.write(bytes);
                out.flush();
            }
            String expected = sha256(bytes);
            String actual;
            try (InputStream in = context.getContentResolver().openInputStream(fileUri)) {
                if (in == null) throw new Exception("Could not verify backup file");
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
                actual = sha256(b.toByteArray());
            }
            if (!expected.equals(actual)) throw new Exception("Backup verification failed");
            p.edit().putLong(KEY_LAST_OK, System.currentTimeMillis()).putString(KEY_LAST_ERROR, "").putInt(KEY_RETRY, 0).apply();
            scheduleBackgroundBackup(context, 6 * 60 * 60 * 1000L);
        } catch (Exception e) {
            int retry = p.getInt(KEY_RETRY, 0) + 1;
            long[] waits = {5*60*1000L, 15*60*1000L, 30*60*1000L, 60*60*1000L, 2*60*60*1000L};
            long wait = waits[Math.min(retry - 1, waits.length - 1)];
            p.edit().putString(KEY_LAST_ERROR, e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())).putInt(KEY_RETRY, retry).apply();
            scheduleBackgroundBackup(context, wait);
        }
    }

    private static Uri findOrCreateFile(Context context, Uri treeUri, String name, String mime) throws Exception {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (android.database.Cursor c = context.getContentResolver().query(children, projection, null, null, null)) {
            if (c != null) while (c.moveToNext()) {
                if (name.equals(c.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
            }
        }
        return DocumentsContract.createDocument(context.getContentResolver(), treeUri, mime, name);
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(data);
        StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format("%02x", b));
        return s.toString();
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == PICK_FILE) {
            Uri[] uris = null;
            if (result == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount(); uris = new Uri[n];
                    for (int x=0;x<n;x++) uris[x]=data.getClipData().getItemAt(x).getUri();
                } else if (data.getData() != null) uris = new Uri[]{data.getData()};
            }
            if (fileCallback != null) fileCallback.onReceiveValue(uris);
            fileCallback = null;
        } else if (code == SAVE_FILE) {
            if (result == RESULT_OK && data != null && data.getData() != null && pendingContent != null) {
                try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                    if (out != null) out.write(pendingContent.getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) { }
            }
            pendingContent = pendingName = pendingMime = null;
        } else if (code == PICK_BACKUP_FOLDER) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); } catch (Exception ignored) { }
                prefs().edit().putString(KEY_URI, uri.toString()).putString(KEY_LAST_ERROR, "").putInt(KEY_RETRY, 0).apply();
                performBackup();
                webView.evaluateJavascript("window.mapingBackupFolderConfigured&&window.mapingBackupFolderConfigured()", null);
            }
        }
    }

    @Override public void onBackPressed() {
        webView.evaluateJavascript("(function(){var a=document.querySelector('.view.active');return a?a.id:''})()", id -> {
            if (!"\"home\"".equals(id)) webView.evaluateJavascript("openView('home')", null); else super.onBackPressed();
        });
    }
}
