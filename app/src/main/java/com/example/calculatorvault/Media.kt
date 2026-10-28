package com.example.calculatorvault

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

internal enum class VType { IMAGE, VIDEO, AUDIO, APK, OTHER }

internal fun typeOf(name: String): VType = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> VType.IMAGE
    "mp4", "mkv", "3gp", "webm", "mov", "avi", "m4v" -> VType.VIDEO
    "mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "amr", "wma" -> VType.AUDIO
    "apk" -> VType.APK
    else -> VType.OTHER
}

internal fun mimeFor(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext == "apk") return "application/vnd.android.package-archive"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

internal fun safeName(raw: String): String =
    raw.substringAfterLast('/').substringAfterLast(':')
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(80).ifBlank { "vault_file" }

private fun ensureExt(name: String, type: VType): String =
    if (typeOf(name) == type) name else name + when (type) {
        VType.IMAGE -> ".jpg"; VType.VIDEO -> ".mp4"; VType.AUDIO -> ".mp3"; else -> ""
    }

internal data class PickItem(val uri: Uri, val name: String, val type: VType, val dateAdded: Long)

private const val QUERY_LIMIT = 5000

internal fun queryDeviceMedia(context: Context): List<PickItem> {
    val items = mutableListOf<PickItem>()
    val proj = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATE_ADDED
    )
    fun q(collection: Uri, type: VType) {
        try {
            context.contentResolver.query(
                collection, proj, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cur ->
                val idC = cur.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nC = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dC = cur.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                var n = 0
                while (cur.moveToNext() && n < QUERY_LIMIT) {
                    items.add(
                        PickItem(
                            ContentUris.withAppendedId(collection, cur.getLong(idC)),
                            cur.getString(nC) ?: "file", type, cur.getLong(dC)
                        )
                    )
                    n++
                }
            }
        } catch (_: Exception) { /* permission for this collection not granted */ }
    }
    q(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, VType.IMAGE)
    q(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, VType.VIDEO)
    q(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, VType.AUDIO)
    return items.sortedByDescending { it.dateAdded }
}

private fun decodeSampled(context: Context, uri: Uri, px: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
    val o2 = BitmapFactory.Options().apply { inSampleSize = s }
    return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
}

internal fun makeThumbBitmap(context: Context, uri: Uri, type: VType, px: Int): Bitmap? {
    var bmp: Bitmap? = null
    try {
        if (Build.VERSION.SDK_INT >= 29 &&
            (type == VType.IMAGE || type == VType.VIDEO || type == VType.AUDIO)
        ) {
            bmp = context.contentResolver.loadThumbnail(uri, Size(px, px), null)
        }
    } catch (_: Throwable) { }
    if (bmp == null) {
        try {
            bmp = when (type) {
                VType.IMAGE -> decodeSampled(context, uri, px)
                VType.VIDEO -> {
                    val r = MediaMetadataRetriever()
                    try { r.setDataSource(context, uri); r.getFrameAtTime(0) } finally { r.release() }
                }
                else -> null
            }
        } catch (_: Throwable) { }
    }
    return bmp
}

internal fun queryName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0)?.let { n -> return n } }
    } catch (_: Exception) { }
    return uri.lastPathSegment ?: "file"
}

private fun querySize(context: Context, uri: Uri): Long {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0) }
    } catch (_: Exception) { }
    return -1L
}

/** Encrypts one item into the vault, verifies it can be read back, builds an encrypted thumbnail. */
internal fun importOne(context: Context, uri: Uri, rawName: String, type: VType): Boolean {
    val name = ensureExt(safeName(rawName), type)
    val vf = VaultCrypto.newVaultFile(context)
    try {
        val input = context.contentResolver.openInputStream(uri) ?: return false
        input.use { VaultCrypto.encrypt(context, it, name, querySize(context, uri), vf) }
        if (!VaultCrypto.verify(context, vf)) {
            VaultCrypto.deleteWithThumb(vf); return false
        }
        makeThumbBitmap(context, uri, type, 320)?.let { bmp ->
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            runCatching { VaultCrypto.saveThumb(context, vf, bos.toByteArray()) }
        }
        return true
    } catch (_: Throwable) {
        VaultCrypto.deleteWithThumb(vf)
        return false
    }
}

/**
 * Deletes the original gallery files after they are safely in the vault.
 * Android 11+: ONE system "allow delete?" dialog for the whole batch.
 * Android 10: one dialog per item (system rule). Android 8–9: silent (storage permission).
 */
internal class Deleter(
    private val context: Context,
    private val onDone: (deleted: Int, failed: Int) -> Unit
) {
    var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private val queue = ArrayDeque<Uri>()
    private var batch = false
    private var total = 0
    private var failed = 0
    private var lastLaunched: Uri? = null

    fun start(uris: List<Uri>) {
        total = uris.size; failed = 0; queue.clear(); lastLaunched = null
        if (uris.isEmpty()) { finish(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
                batch = true
                launcher!!.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                return
            } catch (_: Exception) { batch = false }
        }
        queue.addAll(uris)
        next()
    }

    fun onResult(ok: Boolean) {
        if (batch) {
            batch = false
            if (!ok) failed = total
            finish(); return
        }
        if (!ok && queue.isNotEmpty()) { queue.removeFirst(); failed++ }
        next()
    }

    private fun next() {
        while (queue.isNotEmpty()) {
            val uri = queue.first()
            try {
                val rows = context.contentResolver.delete(uri, null, null)
                queue.removeFirst()
                if (rows <= 0) failed++
            } catch (e: SecurityException) {
                val sender = if (Build.VERSION.SDK_INT >= 29 && e is RecoverableSecurityException && uri != lastLaunched)
                    e.userAction.actionIntent.intentSender else null
                if (sender != null && launcher != null) {
                    try {
                        lastLaunched = uri
                        launcher!!.launch(IntentSenderRequest.Builder(sender).build())
                        return
                    } catch (_: Exception) { }
                }
                queue.removeFirst(); failed++
            } catch (_: Exception) {
                queue.removeFirst(); failed++
            }
        }
        finish()
    }

    private fun finish() { onDone(total - failed, failed) }
}

/** Decrypts a vault file back into the phone's gallery/music/downloads ("…/Restored"). */
internal fun restoreToDevice(context: Context, vault: File): Boolean {
    try {
        val name = VaultCrypto.readName(vault)
        val type = typeOf(name)
        val mime = mimeFor(name)
        if (Build.VERSION.SDK_INT >= 29) {
            val vol = MediaStore.VOLUME_EXTERNAL_PRIMARY
            val (collection, rel) = when (type) {
                VType.IMAGE -> MediaStore.Images.Media.getContentUri(vol) to "Pictures/Restored"
                VType.VIDEO -> MediaStore.Video.Media.getContentUri(vol) to "Movies/Restored"
                VType.AUDIO -> MediaStore.Audio.Media.getContentUri(vol) to "Music/Restored"
                else -> MediaStore.Downloads.getContentUri(vol) to "Download/Restored"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: return false
            return try {
                val os = resolver.openOutputStream(uri) ?: error("no stream")
                os.use { VaultCrypto.decryptTo(context, vault, it) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                true
            } catch (_: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        } else {
            @Suppress("DEPRECATION")
            val base = Environment.getExternalStoragePublicDirectory(
                when (type) {
                    VType.IMAGE -> Environment.DIRECTORY_PICTURES
                    VType.VIDEO -> Environment.DIRECTORY_MOVIES
                    VType.AUDIO -> Environment.DIRECTORY_MUSIC
                    else -> Environment.DIRECTORY_DOWNLOADS
                }
            )
            val dir = File(base, "Restored").apply { mkdirs() }
            var f = File(dir, name)
            if (f.exists()) f = File(dir, "${System.currentTimeMillis()}_$name")
            FileOutputStream(f).use { VaultCrypto.decryptTo(context, vault, it) }
            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
            return true
        }
    } catch (_: Throwable) {
        return false
    }
}
