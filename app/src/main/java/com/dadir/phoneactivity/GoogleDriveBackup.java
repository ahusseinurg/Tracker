package com.dadir.phoneactivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import androidx.work.Worker;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class GoogleDriveBackup {
    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    static final class Outcome { int copied, skipped, failed, checked; String error=""; }
    private final Context context; private final SharedPreferences prefs; private final Worker worker;
    private String token, rootId; private final long deadline;
    private final Outcome out = new Outcome();
    private final int cursorTarget; private int cursorSeen;

    private GoogleDriveBackup(Context c, SharedPreferences p, Worker w) {
        context=c; prefs=p; worker=w; deadline=System.currentTimeMillis()+TimeUnit.MINUTES.toMillis(12);
        cursorTarget=Math.max(0,p.getInt("drive_scan_cursor",0));
    }

    static synchronized Outcome run(Context c, SharedPreferences p, Worker w) {
        GoogleDriveBackup d=new GoogleDriveBackup(c,p,w);
        try { d.execute(); } catch(Exception e) { d.out.failed++; d.out.error="Google Drive: "+safe(e.getMessage()); }
        return d.out;
    }

    private void execute() throws Exception {
        AuthorizationRequest req=AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE))).build();
        AuthorizationResult auth=Tasks.await(Identity.getAuthorizationClient(context).authorize(req),30,TimeUnit.SECONDS);
        if(auth.hasResolution()) throw new IllegalStateException("Open Maping and reconnect Google Drive");
        token=auth.getAccessToken();
        if(token==null||token.isEmpty()) throw new IllegalStateException("Drive authorization returned no access token");
        rootId=findOrCreateFolder("Maping Backup","root");
        Set<String> sources=new LinkedHashSet<>(prefs.getStringSet("folder_uris",new LinkedHashSet<>()));
        String legacy=prefs.getString("folder_uri",null); if(legacy!=null)sources.add(legacy);
        if(sources.isEmpty()) throw new IllegalStateException("No source folders are connected");
        for(String raw:sources){ if(stop())break; DocumentFile root=DocumentFile.fromTreeUri(context,Uri.parse(raw));
            if(root!=null&&root.canRead()) walk(root,root.getName()==null?"Phone files":root.getName()); else out.failed++; }
        if(!stop()&&out.failed==0)prefs.edit().putBoolean("drive_initial_backup_complete",true).putInt("drive_scan_cursor",0).apply();
    }

    private void walk(DocumentFile dir,String path) throws Exception {
        DocumentFile[] children; try{children=dir.listFiles();}catch(Exception e){out.failed++;return;}
        for(DocumentFile f:children){ if(stop())return; String n=f.getName()==null?"Unnamed":f.getName();
            if(f.isDirectory())walk(f,path+"/"+n); else if(f.isFile()){
                if(cursorSeen<cursorTarget){cursorSeen++;continue;}
                upload(f,n,path); cursorSeen++;
                prefs.edit().putInt("drive_scan_cursor",cursorSeen).apply();
            } }
    }

    private void upload(DocumentFile source,String name,String path) throws Exception {
        String lower=name.toLowerCase(Locale.US);
        if(lower.startsWith(".nomedia")){out.skipped++;return;}
        long start=prefs.getLong("backup_start_ms",0); boolean includeExisting=prefs.getBoolean("backup_include_existing",false); long modified=source.lastModified();
        if(!includeExisting&&(modified<=0||modified<start)){out.skipped++;return;}
        out.checked++; if(out.checked%10==0)prefs.edit().putInt("sync_progress_files",out.checked).putString("sync_phase","Uploading to Google Drive").apply();
        String mime=source.getType()==null?"application/octet-stream":source.getType();
        String category=category(path,lower,mime);
        if(!enabled(category)){out.skipped++;return;}
        String folder=findOrCreateFolder(category,rootId);
        JSONObject existing=findFile(name,folder);
        if(existing!=null&&existing.optLong("size",-1)==source.length()){out.skipped++;return;}
        String finalName=existing==null?name:unique(name,source.lastModified());
        resumableUpload(source,finalName,mime,folder); out.copied++;
    }

    private String findOrCreateFolder(String name,String parent) throws Exception {
        JSONObject f=findFile(name,parent); if(f!=null&&f.optString("mimeType").equals("application/vnd.google-apps.folder"))return f.getString("id");
        JSONObject meta=new JSONObject().put("name",name).put("mimeType","application/vnd.google-apps.folder");
        if(!"root".equals(parent))meta.put("parents",new JSONArray().put(parent));
        JSONObject made=request("POST","https://www.googleapis.com/drive/v3/files?fields=id",meta.toString()); return made.getString("id");
    }

    private JSONObject findFile(String name,String parent) throws Exception {
        String q="name = '"+name.replace("'","\\'")+"' and '"+parent+"' in parents and trashed = false";
        JSONObject r=request("GET","https://www.googleapis.com/drive/v3/files?q="+URLEncoder.encode(q,"UTF-8")+"&fields=files(id,name,size,mimeType)&pageSize=10",null);
        JSONArray a=r.optJSONArray("files"); return a!=null&&a.length()>0?a.getJSONObject(0):null;
    }

    private void resumableUpload(DocumentFile source,String name,String mime,String parent) throws Exception {
        JSONObject meta=new JSONObject().put("name",name).put("parents",new JSONArray().put(parent));
        HttpURLConnection start=conn("POST","https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id");
        start.setRequestProperty("Content-Type","application/json; charset=UTF-8"); start.setRequestProperty("X-Upload-Content-Type",mime); start.setDoOutput(true);
        try(OutputStream os=start.getOutputStream()){os.write(meta.toString().getBytes(StandardCharsets.UTF_8));}
        int sc=start.getResponseCode(); if(sc<200||sc>=300)throw new IllegalStateException("Drive upload start failed: "+sc);
        String location=start.getHeaderField("Location"); start.disconnect(); if(location==null)throw new IllegalStateException("Drive did not return upload location");
        HttpURLConnection put=conn("PUT",location); put.setRequestProperty("Content-Type",mime); put.setDoOutput(true); put.setFixedLengthStreamingMode(source.length());
        try(InputStream in=context.getContentResolver().openInputStream(source.getUri());OutputStream os=put.getOutputStream()){
            if(in==null)throw new IllegalStateException("Cannot read source file"); byte[] b=new byte[65536]; int n; while((n=in.read(b))!=-1){if(worker.isStopped())throw new IllegalStateException("Backup stopped");os.write(b,0,n);}}
        int pc=put.getResponseCode(); if(pc<200||pc>=300)throw new IllegalStateException("Drive upload failed: "+pc); put.disconnect();
    }

    private JSONObject request(String method,String url,String body) throws Exception { HttpURLConnection c=conn(method,url); if(body!=null){c.setRequestProperty("Content-Type","application/json");c.setDoOutput(true);try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}} int code=c.getResponseCode(); InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream(); String s=read(in);c.disconnect();if(code<200||code>=300)throw new IllegalStateException("Drive API "+code+": "+s);return new JSONObject(s); }
    private HttpURLConnection conn(String method,String url) throws Exception {HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod(method);c.setConnectTimeout(30000);c.setReadTimeout(60000);c.setRequestProperty("Authorization","Bearer "+token);return c;}
    private static String read(InputStream in)throws Exception{if(in==null)return"";byte[] b=new byte[8192];StringBuilder s=new StringBuilder();int n;while((n=in.read(b))!=-1)s.append(new String(b,0,n,StandardCharsets.UTF_8));in.close();return s.toString();}
    private boolean stop(){return worker.isStopped()||System.currentTimeMillis()>=deadline;}
    private boolean enabled(String c){return prefs.getBoolean(c.equals("Statuses")?"type_statuses":c.equals("Pictures")?"type_pictures":c.equals("Videos")?"type_videos":c.equals("Audio")?"type_audio":"type_documents",true);}
    private static String category(String path,String name,String mime){
        if(isStatus(path))return "Statuses";
        if(mime.startsWith("image/")||ends(name,"jpg","jpeg","png","gif","webp","heic","heif"))return "Pictures";
        if(mime.startsWith("video/")||ends(name,"mp4","3gp","mkv","webm","mov","avi"))return "Videos";
        if(mime.startsWith("audio/")||ends(name,"opus","ogg","m4a","mp3","aac","amr","wav","flac"))return "Audio";
        if(name.startsWith("aud-")||name.startsWith("ptt-"))return "Audio";
        return "Documents";
    }
    private static boolean ends(String name,String... extensions){for(String e:extensions)if(name.endsWith("."+e))return true;return false;}
    private static boolean isStatus(String p){String n=p.toLowerCase(Locale.US);return n.contains("/.statuses/")||n.contains("/statuses/")||n.contains("/status archive/");}
    private static String unique(String n,long m){int d=n.lastIndexOf('.');return d<=0?n+"-"+m:n.substring(0,d)+"-"+m+n.substring(d);}
    private static String safe(String s){return s==null?"Unknown error":s.length()>300?s.substring(0,300):s;}
}
