package com.dadir.phoneactivity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends SecureActivity {
    private static final int CREATE_CSV = 40;
    private final int navy = Color.rgb(16, 42, 67);
    private final int blue = Color.rgb(37, 99, 235);
    private UsageRepository repository;
    private LinearLayout content;
    private TextView permissionStatus;
    private Button dayButton;
    private Button weekButton;
    private List<UsageRepository.AppRow> currentRows = new ArrayList<>();
    private long rangeStart;
    private long rangeEnd;
    private int selectedDays = 1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        repository = new UsageRepository(this);
        setContentView(buildScreen());
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 247, 250));

        LinearLayout header = box(navy, 22);
        TextView title = text("Phone Activity", 26, Color.WHITE, true);
        TextView subtitle = text("Private, on-device activity overview", 14, Color.rgb(210, 225, 240), false);
        header.addView(title);
        header.addView(subtitle);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(28));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }

    private void refresh() {
        content.removeAllViews();
        addConsentCard();
        if (!repository.hasAccess()) return;

        rangeEnd = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(rangeEnd);
        if (selectedDays == 1) cal.set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        else cal.add(Calendar.DAY_OF_YEAR, -(selectedDays - 1));
        rangeStart = cal.getTimeInMillis();

        currentRows = repository.apps(rangeStart, rangeEnd);
        UsageRepository.DeviceSummary device = repository.deviceSummary(rangeStart, rangeEnd);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        dayButton = action("Today", () -> { selectedDays = 1; refresh(); });
        weekButton = action("7 days", () -> { selectedDays = 7; refresh(); });
        filters.addView(dayButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        filters.addView(space(8));
        filters.addView(weekButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(filters);
        tintSelection();

        long total = 0;
        for (UsageRepository.AppRow row : currentRows) total += row.foregroundMs;
        LinearLayout summary = box(Color.WHITE, 18);
        summary.addView(text("SUMMARY", 12, Color.DKGRAY, true));
        summary.addView(text(UsageRepository.duration(total) + " app time", 27, navy, true));
        summary.addView(text(device.unlocks + " unlocks   •   " + device.screenOns + " screen activations", 15, Color.DKGRAY, false));
        content.addView(summary, margins(0, 14, 0, 0));

        content.addView(text("Most used apps", 20, navy, true), margins(2, 22, 0, 10));
        if (currentRows.isEmpty()) {
            content.addView(text("No activity is available for this period yet.", 16, Color.DKGRAY, false));
        } else {
            int max = Math.min(currentRows.size(), 25);
            for (int i = 0; i < max; i++) addAppRow(currentRows.get(i), i + 1);
        }

        Button export = action("Export full CSV report", this::createCsv);
        content.addView(export, margins(0, 18, 0, 0));
        content.addView(text("Data stays on this phone unless you choose Export. This prototype does not capture passwords, message contents, calls, audio, photos, or keystrokes.", 13, Color.DKGRAY, false), margins(4, 16, 4, 0));
    }

    private void addConsentCard() {
        boolean granted = repository.hasAccess();
        LinearLayout card = box(Color.WHITE, 18);
        card.addView(text("Usage access", 18, navy, true));
        permissionStatus = text(granted ? "Enabled" : "Permission required", 15,
                granted ? Color.rgb(21, 128, 61) : Color.rgb(180, 60, 35), true);
        card.addView(permissionStatus);
        card.addView(text(granted
                ? "Android is providing on-device app and screen activity statistics."
                : "Tap below, select Phone Activity, and enable Permit usage access.", 14, Color.DKGRAY, false));
        if (!granted) {
            Button grant = action("Open usage access settings", () -> {
                Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            });
            card.addView(grant, margins(0, 14, 0, 0));
        }
        content.addView(card);
        Button media = action("Open phone media archive", () ->
                startActivity(new Intent(this, MediaLibraryActivity.class)));
        content.addView(media, margins(0, 12, 0, 0));
    }

    private void addAppRow(UsageRepository.AppRow row, int rank) {
        LinearLayout item = box(Color.WHITE, 14);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(rank + ".  " + row.label, 16, navy, true);
        TextView duration = text(UsageRepository.duration(row.foregroundMs), 16, blue, true);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(duration);
        item.addView(top);
        item.addView(text(row.packageName + "\nLast used " + UsageRepository.lastUsed(row.lastUsed), 12, Color.GRAY, false));
        content.addView(item, margins(0, 0, 0, 8));
    }

    private void createCsv() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "phone-activity-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".csv");
        startActivityForResult(intent, CREATE_CSV);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CREATE_CSV || resultCode != RESULT_OK || data == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            StringBuilder csv = new StringBuilder("App,Package,Foreground minutes,Last used\n");
            for (UsageRepository.AppRow row : currentRows) {
                csv.append(quote(row.label)).append(',').append(quote(row.packageName)).append(',')
                        .append(row.foregroundMs / 60000).append(',').append(quote(UsageRepository.lastUsed(row.lastUsed))).append('\n');
            }
            out.write(csv.toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Report saved", Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(this, "Could not save report", Toast.LENGTH_LONG).show();
        }
    }

    private String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
    private void tintSelection() {
        dayButton.setAlpha(selectedDays == 1 ? 1f : .55f);
        weekButton.setAlpha(selectedDays == 7 ? 1f : .55f);
    }
    private LinearLayout box(int color, int padding) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        layout.setBackgroundColor(color);
        return layout;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(0, 1.18f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private Button action(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label); button.setTextColor(Color.WHITE); button.setTextSize(14);
        button.setAllCaps(false); button.setBackgroundColor(blue);
        button.setOnClickListener(v -> action.run());
        return button;
    }
    private View space(int width) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1)); return v; }
    private ViewGroup.MarginLayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
