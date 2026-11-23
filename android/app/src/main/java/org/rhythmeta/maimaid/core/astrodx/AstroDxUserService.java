package org.rhythmeta.maimaid.core.astrodx;

import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;

/** Privileged, read-only bridge: no arbitrary paths, shell commands or write operations. */
public final class AstroDxUserService extends IAstroDxService.Stub {
    public AstroDxUserService() {}

    @Override public ParcelFileDescriptor openCache(int userId) {
        if (userId < 0 || userId > 21474) throw new IllegalArgumentException("Invalid user");
        File file = new File("/storage/emulated/" + userId +
            "/Android/data/com.Reflektone.AstroDX/files/cache");
        if (!file.exists()) throw new IllegalStateException("cache_missing");
        if (!file.isFile() || !file.canRead()) throw new IllegalStateException("cache_unreadable");
        if (file.length() > 16 * 1024 * 1024) throw new IllegalStateException("cache_too_large");
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException error) {
            throw new IllegalStateException("cache_unreadable");
        }
    }

    @Override public void destroy() { System.exit(0); }
}
