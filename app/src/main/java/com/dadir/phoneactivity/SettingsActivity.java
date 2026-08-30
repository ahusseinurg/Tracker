package com.dadir.phoneactivity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.app.TimePickerDialog;
import android.app.AlarmManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import java.text.DateFormat;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Date;
import java.util.Calendar;

public class SettingsActivity extends SecureActivity {
    private static final int PICK_BACKUP = 91;
    private static final String PREFS = "media_library";
    private SharedPreferences prefs;
    private LinearLayout body;
    private final int navy=Color.rgb(16,42,67), blue=Color.rgb(37,99,235);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        setContentView(screen()); render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefs != null) {
            if (prefs.getBoolean("auto_sync", true) && prefs.getString("backup_uri", null) != null) ArchiveWorker.schedule(this);
            render();
        }
    }

    private View screen() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(245,247,250));
        LinearLayout header=box(navy); TextView back=text("‹  Maping",15,Color.WHITE,true); back.setOnClickListener(v->finish()); header.addView(back);
        header.addView(text("Settings",27,Color.WHITE,true)); root.addView(header);
        ScrollView scroll=new ScrollView(this); body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(14),dp(14),dp(14),dp(30)); scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1)); return root;
    }

    private void render() {
        body.removeAllViews();
        section("Automatic backup");
        boolean daily="daily_time".equals(prefs.getString("sync_schedule_mode","interval"));
        Button mode=button("Schedule mode: "+(daily?"Daily at a set time":"Repeating interval"));
        mode.setOnClickListener(v->{prefs.edit().putString("sync_schedule_mode",daily?"interval":"daily_time").apply();ArchiveWorker.schedule(this);render();});
        body.addView(mode,margin(0,0,0,7));
        long interval=prefs.getLong("sync_interval_minutes",15);
        if(daily) {
            int hour=prefs.getInt("sync_daily_hour",2), minute=prefs.getInt("sync_daily_minute",0);
            Button time=button("Backup time: "+formatTime(hour,minute));
            time.setOnClickListener(v->new TimePickerDialog(this,(picker,h,m)->{prefs.edit().putInt("sync_daily_hour",h).putInt("sync_daily_minute",m).apply();ArchiveWorker.schedule(this);render();},hour,minute,android.text.format.DateFormat.is24HourFormat(this)).show());
            body.addView(time,margin(0,0,0,7));
            body.addView(text("Android may run the backup after this time if the phone is asleep, offline, force-stopped, or restricted by battery settings.",12,Color.DKGRAY,false),margin(2,0,2,8));
        } else {
            Button schedule=button("Repeat every: "+labelInterval(interval)); schedule.setOnClickListener(v->{ long next=interval==15?60:interval==60?360:interval==360?1440:15; prefs.edit().putLong("sync_interval_minutes",next).apply(); ArchiveWorker.schedule(this); render(); }); body.addView(schedule,margin(0,0,0,7));
        }
        toggle("Wi-Fi only", "wifi_only", false, true);
        toggle("Automatic remote updates", "auto_sync", true, true);
        if(Build.VERSION.SDK_INT>=31) {
            AlarmManager alarms=(AlarmManager)getSystemService(ALARM_SERVICE);
            if(!alarms.canScheduleExactAlarms()) {
                Button allow=button("Allow reliable scheduled backups");
                allow.setBackgroundColor(Color.rgb(190,105,20));
                allow.setOnClickListener(v->{try{startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:"+getPackageName())));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}});
                body.addView(allow,margin(0,0,0,7));
                body.addView(text("Allow Alarms & reminders for Maping, then return here.",12,Color.rgb(165,65,35),false),margin(2,0,2,8));
            }
        }

        section("Source folders");
        Set<String> folders=new LinkedHashSet<>(prefs.getStringSet("folder_uris",new LinkedHashSet<>()));
        if(folders.isEmpty()) body.addView(text("No source folders connected.",14,Color.GRAY,false));
        for(String raw:new LinkedHashSet<>(folders)) {
            DocumentFile f=DocumentFile.fromTreeUri(this,Uri.parse(raw)); String name=f!=null&&f.getName()!=null?f.getName():"Connected folder";
            Button remove=button("Remove: "+name); remove.setBackgroundColor(Color.rgb(150,55,45)); remove.setOnClickListener(v->{ Set<String> next=new LinkedHashSet<>(prefs.getStringSet("folder_uris",new LinkedHashSet<>())); next.remove(raw); prefs.edit().putStringSet("folder_uris",next).apply(); try{getContentResolver().releasePersistableUriPermission(Uri.parse(raw),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){} render(); }); body.addView(remove,margin(0,0,0,7));
        }

        section("Remote destination");
        String backup=prefs.getString("backup_uri",null); body.addView(text(backup==null?"No backup destination selected.":"Backup destination connected.",14,backup==null?Color.rgb(170,60,35):Color.rgb(21,128,61),true));
        Button destination=button(backup==null?"Choose Google Drive folder":"Change Drive destination"); destination.setOnClickListener(v->pickBackup()); body.addView(destination,margin(0,7,0,0));

        section("File types");
        toggle("WhatsApp statuses", "type_statuses", true, false); toggle("Pictures", "type_pictures", true, false); toggle("Videos", "type_videos", true, false); toggle("Audio and voice notes", "type_audio", true, false); toggle("Documents", "type_documents", true, false);

        section("Storage and retention");
        int days=prefs.getInt("retention_days",0); Button retention=button("Keep backups: "+(days==0?"Forever":days+" days")); retention.setOnClickListener(v->{ int next=days==0?30:days==30?90:days==90?365:0; prefs.edit().putInt("retention_days",next).apply(); render(); }); body.addView(retention);
        body.addView(text("Warning: choosing a retention period permanently deletes older files from the selected backup destination during sync.",12,Color.rgb(165,65,35),false),margin(2,6,2,0));

        section("PIN and security");
        toggle("Block screenshots and recent previews", "block_screenshots", true, false);
        changePinForm();

        section("Sync history and errors");
        long last=prefs.getLong("last_sync",0), attempt=prefs.getLong("last_sync_attempt",0), nextAttempt=prefs.getLong("next_sync_attempt",0), duration=prefs.getLong("last_sync_duration_ms",0); int copied=prefs.getInt("last_sync_copied",0), skipped=prefs.getInt("last_sync_skipped",0), failed=prefs.getInt("last_sync_failures",0); String error=prefs.getString("last_sync_error","");
        String history=last==0?"No backup has completed.":"Last completed: "+DateFormat.getDateTimeInstance().format(new Date(last))+"\nCopied: "+copied+"  •  Skipped: "+skipped+"  •  Failed: "+failed+"\nDuration: "+(duration/1000)+" seconds";
        if(attempt>0) history+="\nLast attempt: "+DateFormat.getDateTimeInstance().format(new Date(attempt));
        if(nextAttempt>0) history+="\nNext scheduled attempt: "+DateFormat.getDateTimeInstance().format(new Date(nextAttempt));
        if(error!=null&&!error.isEmpty()) history+="\nProblem: "+error;
        body.addView(text(history,14,error!=null&&!error.isEmpty()?Color.rgb(170,50,35):Color.DKGRAY,false));
        Button run=button("Run automatic sync now"); run.setOnClickListener(v->{ArchiveWorker.runNow(this);Toast.makeText(this,"Sync scheduled",Toast.LENGTH_SHORT).show();}); body.addView(run,margin(0,8,0,0));
    }

    private void toggle(String label,String key,boolean def,boolean reschedule) { boolean value=prefs.getBoolean(key,def); Button b=button(label+": "+(value?"ON":"OFF")); b.setBackgroundColor(value?Color.rgb(21,128,61):Color.rgb(95,105,115)); b.setOnClickListener(v->{prefs.edit().putBoolean(key,!value).apply();if(reschedule){if(key.equals("auto_sync")&&!value){ArchiveWorker.schedule(this);ArchiveWorker.runNow(this);}else if(key.equals("auto_sync")){ArchiveWorker.cancel(this);}else ArchiveWorker.schedule(this);}render();});body.addView(b,margin(0,0,0,7)); }

    private void changePinForm() {
        EditText current=pinField("Current PIN"), next=pinField("New 4–8 digit PIN"), confirm=pinField("Confirm new PIN"); body.addView(current);body.addView(next);body.addView(confirm);
        Button save=button("Change PIN"); save.setOnClickListener(v->{String a=current.getText().toString(),b=next.getText().toString(),c=confirm.getText().toString();if(!LockStore.verify(this,a)){Toast.makeText(this,"Current PIN is incorrect",Toast.LENGTH_LONG).show();return;}if(b.length()<4||b.length()>8||!b.equals(c)){Toast.makeText(this,"New PIN must match and contain 4–8 digits",Toast.LENGTH_LONG).show();return;}if(LockStore.setPin(this,b)){Toast.makeText(this,"PIN changed",Toast.LENGTH_SHORT).show();current.setText("");next.setText("");confirm.setText("");}});body.addView(save,margin(0,7,0,0));
    }

    private void pickBackup(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK_BACKUP);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=PICK_BACKUP||result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);prefs.edit().putString("backup_uri",uri.toString()).putBoolean("auto_sync",true).apply();ArchiveWorker.schedule(this);ArchiveWorker.runNow(this);render();}catch(Exception e){Toast.makeText(this,"Could not save destination access",Toast.LENGTH_LONG).show();}}
    private String labelInterval(long m){return m==15?"15 minutes":m==60?"1 hour":m==360?"6 hours":"24 hours";}
    private String formatTime(int hour,int minute){Calendar c=Calendar.getInstance();c.set(Calendar.HOUR_OF_DAY,hour);c.set(Calendar.MINUTE,minute);return android.text.format.DateFormat.getTimeFormat(this).format(c.getTime());}
    private EditText pinField(String hint){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);return e;}
    private void section(String title){body.addView(text(title,19,navy,true),margin(2,18,0,9));}
    private LinearLayout box(int color){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(18),dp(18),dp(18),dp(18));l.setBackgroundColor(color);return l;}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(14);b.setBackgroundColor(blue);return b;}
    private TextView text(String s,int z,int c,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(z);t.setTextColor(c);t.setLineSpacing(0,1.15f);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private ViewGroup.MarginLayoutParams margin(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
