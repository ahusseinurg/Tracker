package com.dadir.phoneactivity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.InputStream;

public class MediaViewerActivity extends SecureActivity {
    private MediaPlayer player;
    private Button play;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String raw = getIntent().getStringExtra("uri");
        String mime = getIntent().getStringExtra("mime");
        String name = getIntent().getStringExtra("name");
        if (raw == null) { finish(); return; }
        Uri uri = Uri.parse(raw);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.setBackgroundColor(Color.rgb(12,25,38));
        TextView title = new TextView(this);
        title.setText("‹  " + name); title.setTextColor(Color.WHITE); title.setTextSize(17); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setPadding(0,0,0,dp(14)); title.setOnClickListener(v -> finish());
        root.addView(title, new LinearLayout.LayoutParams(-1,-2));

        if (mime != null && mime.startsWith("image/")) showImage(root, uri);
        else if (mime != null && mime.startsWith("video/")) showVideo(root, uri);
        else if (mime != null && mime.startsWith("audio/")) showAudio(root, uri);
        else showDocument(root, uri, mime);
        setContentView(root);
    }

    private void showImage(LinearLayout root, Uri uri) {
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            image.setImageBitmap(BitmapFactory.decodeStream(in));
            root.addView(image, new LinearLayout.LayoutParams(-1,0,1));
        } catch (Exception e) { error(); }
    }

    private void showVideo(LinearLayout root, Uri uri) {
        VideoView video = new VideoView(this);
        MediaController controls = new MediaController(this);
        controls.setAnchorView(video); video.setMediaController(controls); video.setVideoURI(uri);
        root.addView(video, new LinearLayout.LayoutParams(-1,0,1));
        video.setOnPreparedListener(mp -> video.start());
        video.setOnErrorListener((mp,what,extra) -> { error(); return true; });
    }

    private void showAudio(LinearLayout root, Uri uri) {
        TextView icon = new TextView(this); icon.setText("VOICE NOTE / AUDIO"); icon.setTextSize(21); icon.setTextColor(Color.WHITE); icon.setGravity(Gravity.CENTER);
        root.addView(icon, new LinearLayout.LayoutParams(-1,0,1));
        play = new Button(this); play.setText("Play"); play.setTextSize(18); play.setAllCaps(false);play.setTextColor(Color.WHITE);ModernUi.fill(play,ModernUi.BLUE,14);
        root.addView(play, new LinearLayout.LayoutParams(-1,dp(58)));
        try {
            player = new MediaPlayer(); player.setDataSource(this, uri); player.prepareAsync(); play.setEnabled(false);
            player.setOnPreparedListener(mp -> { play.setEnabled(true); play.setOnClickListener(v -> toggle()); });
            player.setOnCompletionListener(mp -> play.setText("Play again"));
            player.setOnErrorListener((mp,w,e) -> { error(); return true; });
        } catch (Exception e) { error(); }
    }

    private void toggle() { if (player == null) return; if (player.isPlaying()) { player.pause(); play.setText("Resume"); } else { player.start(); play.setText("Pause"); } }
    private void showDocument(LinearLayout root, Uri uri, String mime) {
        TextView note = new TextView(this); note.setText("This document type uses an installed document viewer."); note.setTextColor(Color.WHITE); note.setTextSize(17); note.setGravity(Gravity.CENTER);
        root.addView(note,new LinearLayout.LayoutParams(-1,0,1));
        Button open = new Button(this); open.setText("Open document"); open.setAllCaps(false);open.setTextColor(Color.WHITE);ModernUi.fill(open,ModernUi.BLUE,14);
        open.setOnClickListener(v -> { try { Intent i=new Intent(Intent.ACTION_VIEW); i.setDataAndType(uri,mime); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(i); } catch(Exception e) { error(); } });
        root.addView(open,new LinearLayout.LayoutParams(-1,dp(58)));
    }
    private void error() { Toast.makeText(this,"This file could not be opened",Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() { if(player!=null){ player.release(); player=null; } super.onDestroy(); }
    private int dp(int v) { return Math.round(v*getResources().getDisplayMetrics().density); }
}
