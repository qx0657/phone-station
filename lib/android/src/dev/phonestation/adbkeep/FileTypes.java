package dev.phonestation.adbkeep;

import java.util.Locale;

/** 按文件名选一个查看器用的类型。认不出就用二进制流。 */
final class FileTypes {
    private FileTypes() {}

    static String mime(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".ogg")) {
            return "audio/ogg";
        }
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
    }
}
