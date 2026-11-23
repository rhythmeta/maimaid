package org.rhythmeta.maimaid.core.astrodx

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.rhythmeta.maimaid.BuildConfig
import rikka.shizuku.Shizuku

/** Called only after the user presses Import. Listeners and the remote service are always released. */
class AstroDxCacheReader {
    fun isAuthorized(): Boolean =
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    suspend fun read(): ByteArray = withContext(Dispatchers.Main.immediate) {
        check(Shizuku.pingBinder() && !Shizuku.isPreV11()) { "shizuku_unavailable" }
        authorize()
        readAuthorized()
    }

    suspend fun authorize() = withContext(Dispatchers.Main.immediate) {
        check(Shizuku.pingBinder() && !Shizuku.isPreV11()) { "shizuku_unavailable" }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return@withContext
        val result = CompletableDeferred<Int>()
        val listener = Shizuku.OnRequestPermissionResultListener { code, grant ->
            if (code == REQUEST_CODE) result.complete(grant)
        }
        val death = Shizuku.OnBinderDeadListener {
            result.completeExceptionally(IllegalStateException("shizuku_unavailable"))
        }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.addBinderDeadListener(death)
        try {
            Shizuku.requestPermission(REQUEST_CODE)
            check(withTimeout(120_000) { result.await() } == PackageManager.PERMISSION_GRANTED) { "shizuku_denied" }
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
            Shizuku.removeBinderDeadListener(death)
        }
    }

    suspend fun readAuthorized(): ByteArray = withContext(Dispatchers.Main.immediate) {
        check(Shizuku.pingBinder() && !Shizuku.isPreV11()) { "shizuku_unavailable" }
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "shizuku_denied" }
        readAuthorizedInternal()
    }

    private suspend fun readAuthorizedInternal(): ByteArray {
        val connected = CompletableDeferred<IAstroDxService>()
        val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, AstroDxUserService::class.java.name))
            .daemon(false).processNameSuffix("astrodx_reader").tag("astrodx_reader")
            .debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(IAstroDxService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                connected.completeExceptionally(IllegalStateException("shizuku_unavailable"))
            }
        }
        val death = Shizuku.OnBinderDeadListener {
            connected.completeExceptionally(IllegalStateException("shizuku_unavailable"))
        }
        Shizuku.addBinderDeadListener(death)
        try {
            Shizuku.bindUserService(args, connection)
            val service = withTimeout(15_000) { connected.await() }
            val userId = Process.myUserHandle().hashCode()
            return withContext(Dispatchers.IO) {
                val descriptor = service.openCache(userId) ?: error("cache_unreadable")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
                    AstroDxCacheCodec.readLimited(it, AstroDxCacheCodec.MAX_INPUT_BYTES)
                }
            }
        } finally {
            Shizuku.removeBinderDeadListener(death)
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    private companion object { const val REQUEST_CODE = 0xAD01 }
}
