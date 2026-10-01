package dev.phonestation.adbkeep;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileNotFoundException;
import java.nio.file.Path;

/** 只读交出一个已经过路径规则的普通文件，给系统查看器用。 */
public final class SharedFileProvider extends ContentProvider {
    static final String AUTHORITY = "dev.phonestation.adbkeep.file";

    static Uri uri(Path path) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(path.toString()).build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.indexOf('w') >= 0) {
            throw new FileNotFoundException("只读");
        }
        String raw = uri.getPath();
        if (raw == null || raw.isEmpty()) {
            throw new FileNotFoundException("没有路径");
        }
        try {
            Path path = FileOps.device().openable(raw);
            return ParcelFileDescriptor.open(path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileFailure error) {
            throw new FileNotFoundException(error.getMessage());
        } catch (RuntimeException error) {
            throw new FileNotFoundException(error.getMessage());
        }
    }

    @Override
    public String getType(Uri uri) {
        String raw = uri.getPath();
        if (raw == null) {
            return "application/octet-stream";
        }
        int slash = raw.lastIndexOf('/');
        String name = slash >= 0 ? raw.substring(slash + 1) : raw;
        return FileTypes.mime(name);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
