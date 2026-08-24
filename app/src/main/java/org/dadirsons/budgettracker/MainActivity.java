package org.dadirsons.budgettracker;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private String pendingContent, pendingName, pendingMime;
    private static final int PICK_FILE = 201, SAVE_FILE = 202;

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
        webView.setWebViewClient(new WebViewClient());
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
    }

    public class AppBridge {
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

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == PICK_FILE) {
            Uri[] uris = null;
            if (result == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    uris = new Uri[n];
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
        }
    }

    @Override public void onBackPressed() {
        webView.evaluateJavascript("(function(){var a=document.querySelector('.view.active');return a?a.id:''})()", id -> {
            if (!"\"home\"".equals(id)) webView.evaluateJavascript("openView('home')", null); else super.onBackPressed();
        });
    }
}
