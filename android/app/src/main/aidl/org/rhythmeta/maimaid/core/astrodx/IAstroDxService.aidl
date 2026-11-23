package org.rhythmeta.maimaid.core.astrodx;
import android.os.ParcelFileDescriptor;
interface IAstroDxService {
    ParcelFileDescriptor openCache(int userId) = 0;
    void destroy() = 16777114;
}
