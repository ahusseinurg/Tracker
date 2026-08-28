package com.dadir.phoneactivity;

import android.net.Uri;

final class MediaItem {
    final String name;
    final String mime;
    final Uri uri;
    final long size;
    final long modified;
    final boolean archived;
    final boolean status;

    MediaItem(String name, String mime, Uri uri, long size, long modified, boolean archived, boolean status) {
        this.name = name;
        this.mime = mime == null ? "application/octet-stream" : mime;
        this.uri = uri;
        this.size = size;
        this.modified = modified;
        this.archived = archived;
        this.status = status;
    }

    String category() {
        if (status) return "Statuses";
        if (mime.startsWith("image/")) return "Pictures";
        if (mime.startsWith("video/")) return "Videos";
        if (mime.startsWith("audio/")) return "Audio";
        return "Documents";
    }
}
