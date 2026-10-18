package org.familycircle.android.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.familycircle.android.api.ApiClient
import org.familycircle.android.api.ApiException
import org.familycircle.android.util.Logger
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Profile pictures (`GET /users/{id}/avatar?v={avatar_version}` with the bearer token), cached in
 * memory and in the app's cache directory. The version is part of the key, so a changed picture is
 * fetched again and the old file is deleted. The cache is wiped on sign-out.
 */
class AvatarRepository(private val api: ApiClient, private val dir: File) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Keys the server answered with 404 (no picture / not visible) – not asked again this process. */
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun cached(userId: String, version: Long?): Bitmap? = memory.get(key(userId, version))

    /** Returns the picture or null (no picture, not visible, offline). Never throws. */
    suspend fun load(userId: String, version: Long?): Bitmap? {
        val key = key(userId, version)
        memory.get(key)?.let { return it }
        if (key in missing) return null
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock {
            memory.get(key) ?: withContext(Dispatchers.IO) { loadLocked(userId, version, key) }
        }
    }

    private suspend fun loadLocked(userId: String, version: Long?, key: String): Bitmap? {
        val file = fileFor(userId, version) ?: return null
        if (file.isFile) {
            decode(file.readBytesOrNull())?.let {
                memory.put(key, it)
                return it
            }
            file.delete()
        }
        val bytes = try {
            api.avatar(userId, version)
        } catch (e: ApiException) {
            Logger.w(TAG, "avatar not loaded", e)
            return null
        }
        if (bytes == null) {
            missing += key
            return null
        }
        val bitmap = decode(bytes) ?: return null
        try {
            dir.mkdirs()
            // Older versions of the same user's picture are obsolete.
            dir.listFiles { f -> f.name.startsWith(safe(userId) + "_") }?.forEach { it.delete() }
            file.writeBytes(bytes)
        } catch (e: IOException) {
            Logger.w(TAG, "avatar not cached on disk", e)
        }
        memory.put(key, bitmap)
        return bitmap
    }

    /** Deletes everything (sign-out / change of server). */
    fun clear() {
        memory.evictAll()
        missing.clear()
        dir.deleteRecursively()
    }

    private fun fileFor(userId: String, version: Long?): File? {
        val id = safe(userId)
        if (id.isEmpty()) return null
        return File(dir, "${id}_${version ?: 0}.img")
    }

    private fun File.readBytesOrNull(): ByteArray? = try {
        readBytes()
    } catch (_: IOException) {
        null
    }

    private fun decode(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val TAG = "Avatars"
        const val TARGET_PX = 192
        const val MEMORY_BYTES = 8 * 1024 * 1024
        private val UNSAFE = Regex("[^A-Za-z0-9-]")

        fun key(userId: String, version: Long?) = "$userId:${version ?: 0}"

        fun safe(userId: String) = userId.replace(UNSAFE, "").take(64)
    }
}
