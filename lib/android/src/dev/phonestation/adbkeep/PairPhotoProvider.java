package dev.phonestation.adbkeep;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** 只交出缓存里的一张配对照片，给系统相机写入。应用自己不申请相机权限。 */
public final class PairPhotoProvider extends ContentProvider {
    static final String AUTHORITY = "dev.phonestation.adbkeep.pairphoto";

    static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/pair-photo.jpg");
    }

    static File file(android.content.Context context) {
        return new File(context.getCacheDir(), "pair-photo.jpg");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (uri == null || !"/pair-photo.jpg".equals(uri.getPath()) || getContext() == null) {
            throw new FileNotFoundException("没有这张照片");
        }
        File photo = file(getContext());
        int bits = ParcelFileDescriptor.MODE_READ_ONLY;
        if (mode != null && mode.indexOf('w') >= 0) {
            bits = ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        return ParcelFileDescriptor.open(photo, bits);
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
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
