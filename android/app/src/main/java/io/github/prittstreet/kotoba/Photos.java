package io.github.prittstreet.kotoba;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Makes a photo one the page can read. Samsung phones can save HEIF photos ("high efficiency
 * pictures"), which the WebView cannot decode, and the S23 Ultra takes 50 and 200 MP photos, too
 * big to decode in the page. Those become a JPEG of at most about MAX_SIDE pixels, upright (EXIF
 * orientation applied). Other photos are returned as they are. Not on the main thread.
 */
final class Photos {
    private static final long BIG = 8L * 1024 * 1024;
    private static final int MAX_SIDE = 2048;

    private Photos() {}

    static Uri usable(MainActivity app, Uri uri) {
        if (uri == null || Build.VERSION.SDK_INT < 28) return uri;
        ContentResolver cr = app.getContentResolver();
        String type;
        try {
            type = cr.getType(uri);
        } catch (RuntimeException e) {
            type = null;
        }
        boolean readable = "image/jpeg".equals(type) || "image/png".equals(type)
                || "image/webp".equals(type) || "image/gif".equals(type);
        if (readable && length(cr, uri) <= BIG) return uri;
        return toJpeg(app, uri);
    }

    private static long length(ContentResolver cr, Uri uri) {
        try (AssetFileDescriptor fd = cr.openAssetFileDescriptor(uri, "r")) {
            return fd == null ? 0 : fd.getLength(); // -1 when unknown: left as it is
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    @RequiresApi(28)
    private static Uri toJpeg(MainActivity app, Uri uri) {
        Bitmap bmp = null;
        try {
            bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(app.getContentResolver(), uri),
                    (decoder, info, source) -> {
                        int side = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                        decoder.setTargetSampleSize(Math.max(1, (side + MAX_SIDE - 1) / MAX_SIDE));
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
            File dir = new File(app.getCacheDir(), "photos");
            if (!dir.isDirectory() && !dir.mkdirs()) return uri;
            File out = File.createTempFile("photo-", ".jpg", dir);
            try (OutputStream os = new FileOutputStream(out)) {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, os)) return uri;
            }
            return FileProvider.getUriForFile(app, app.getPackageName() + ".files", out);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return uri; // the page tries with the original
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }
}
