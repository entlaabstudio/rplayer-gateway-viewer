package com.technotramp.unexpectedtracks;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class MimeTypes {
    private static final Map<String, String> MIME_BY_EXTENSION = new HashMap<>();

    static {
        MIME_BY_EXTENSION.put("appcache", "text/cache-manifest; charset=utf-8");
        MIME_BY_EXTENSION.put("css", "text/css; charset=utf-8");
        MIME_BY_EXTENSION.put("gif", "image/gif");
        MIME_BY_EXTENSION.put("htm", "text/html; charset=utf-8");
        MIME_BY_EXTENSION.put("html", "text/html; charset=utf-8");
        MIME_BY_EXTENSION.put("ico", "image/x-icon");
        MIME_BY_EXTENSION.put("jpeg", "image/jpeg");
        MIME_BY_EXTENSION.put("jpg", "image/jpeg");
        MIME_BY_EXTENSION.put("js", "text/javascript; charset=utf-8");
        MIME_BY_EXTENSION.put("json", "application/json; charset=utf-8");
        MIME_BY_EXTENSION.put("m4a", "audio/mp4");
        MIME_BY_EXTENSION.put("mp3", "audio/mpeg");
        MIME_BY_EXTENSION.put("mp4", "video/mp4");
        MIME_BY_EXTENSION.put("ogg", "audio/ogg");
        MIME_BY_EXTENSION.put("png", "image/png");
        MIME_BY_EXTENSION.put("svg", "image/svg+xml; charset=utf-8");
        MIME_BY_EXTENSION.put("wasm", "application/wasm");
        MIME_BY_EXTENSION.put("webm", "video/webm");
        MIME_BY_EXTENSION.put("webp", "image/webp");
        MIME_BY_EXTENSION.put("woff", "font/woff");
        MIME_BY_EXTENSION.put("woff2", "font/woff2");
        MIME_BY_EXTENSION.put("zip", "application/zip");
    }

    private MimeTypes() {
    }

    static String fromPath(String path, String fallback) {
        String extension = extensionFromPath(path);
        String mimeType = MIME_BY_EXTENSION.get(extension);

        if (mimeType != null) {
            return mimeType;
        }

        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback;
        }

        return "application/octet-stream";
    }

    private static String extensionFromPath(String path) {
        int queryStart = path.indexOf('?');
        String cleanPath = queryStart >= 0 ? path.substring(0, queryStart) : path;
        int lastDot = cleanPath.lastIndexOf('.');

        if (lastDot < 0 || lastDot == cleanPath.length() - 1) {
            return "";
        }

        return cleanPath.substring(lastDot + 1).toLowerCase(Locale.ROOT);
    }
}
