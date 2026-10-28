package com.example.calculatorvault

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal const val PREFS = "vault_setup"
private const val PIN_HASH = "pin_hash"
private const val SALT = "pin_salt"
private const val PIN_ALGO = "pin_algo"
private const val KEY_RAW = "vault_key"
private const val KEY_WRAPPED = "vault_key_ks"
private const val FAILS = "pin_fails"
private const val LOCK_UNTIL = "pin_lock_until"
private const val PBKDF_ITER = 120_000

private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
private fun unb64(s: String?): ByteArray = Base64.decode(s ?: "", Base64.NO_WRAP)

/** Wraps the vault key with a non-exportable Android Keystore key. */
private object KeystoreWrap {
    private const val ALIAS = "vault_wrap_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun wrap(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(data)
    }

    fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

internal object VaultKeys {
    @Volatile private var cached: SecretKey? = null

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clear() { cached = null }

    fun exists(c: Context): Boolean {
        val p = prefs(c)
        return p.contains(KEY_WRAPPED) || p.contains(KEY_RAW)
    }

    fun create(c: Context) {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        store(prefs(c), bytes)
    }

    fun get(c: Context): SecretKey {
        cached?.let { return it }
        val p = prefs(c)
        val wrapped = p.getString(KEY_WRAPPED, null)
        val raw: ByteArray = if (wrapped != null) {
            KeystoreWrap.unwrap(unb64(wrapped))
        } else {
            val bytes = unb64(p.getString(KEY_RAW, null) ?: error("Vault key missing"))
            store(p, bytes) // upgrade old plain-text key to Keystore-wrapped
            bytes
        }
        return SecretKeySpec(raw, "AES").also { cached = it }
    }

    private fun store(p: SharedPreferences, bytes: ByteArray) {
        try {
            val w = KeystoreWrap.wrap(bytes)
            if (!KeystoreWrap.unwrap(w).contentEquals(bytes)) error("keystore self-test failed")
            p.edit().putString(KEY_WRAPPED, b64(w)).remove(KEY_RAW).commit()
        } catch (_: Exception) {
            // Some phones have a broken Keystore: fall back so the vault still works.
            p.edit().putString(KEY_RAW, b64(bytes)).commit()
        }
    }
}

internal object PinStore {
    fun isConfigured(c: Context): Boolean = VaultKeys.prefs(c).contains(PIN_HASH)

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, PBKDF_ITER, 256)).encoded

    private fun sha(pin: String, salt: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").also { it.update(salt) }
            .digest(pin.toByteArray(Charsets.UTF_8))

    /** Sets (or changes) the PIN. The vault key is independent of the PIN, so files stay readable. */
    fun setPin(c: Context, pin: String) {
        if (!VaultKeys.exists(c)) VaultKeys.create(c)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        VaultKeys.prefs(c).edit()
            .putString(SALT, b64(salt))
            .putString(PIN_HASH, b64(pbkdf2(pin, salt)))
            .putString(PIN_ALGO, "pbkdf2")
            .putInt(FAILS, 0).putLong(LOCK_UNTIL, 0L)
            .commit()
    }

    /**
     * True if [pin] is right. After 5 wrong guesses further tries are silently refused for a
     * growing delay (30s … 10min) — the calculator never shows a "locked" message.
     */
    fun verify(c: Context, pin: String, countFailure: Boolean): Boolean {
        val p = VaultKeys.prefs(c)
        val now = System.currentTimeMillis()
        if (now < p.getLong(LOCK_UNTIL, 0L)) return false
        val salt = unb64(p.getString(SALT, null))
        val expected = unb64(p.getString(PIN_HASH, null))
        val legacy = p.getString(PIN_ALGO, null) == null
        val ok = MessageDigest.isEqual(if (legacy) sha(pin, salt) else pbkdf2(pin, salt), expected)
        if (ok) {
            if (legacy) setPin(c, pin) else p.edit().putInt(FAILS, 0).putLong(LOCK_UNTIL, 0L).apply()
            return true
        }
        if (countFailure) {
            val fails = p.getInt(FAILS, 0) + 1
            val e = p.edit().putInt(FAILS, fails)
            if (fails >= 5) {
                val delay = minOf(30_000L shl (fails - 5).coerceAtMost(5), 600_000L)
                e.putLong(LOCK_UNTIL, now + delay)
            }
            e.apply()
        }
        return false
    }
}

internal object CacheDirs {
    fun clear(c: Context) {
        listOf("preview", "play").forEach { runCatching { File(c.cacheDir, it).deleteRecursively() } }
    }
}

/**
 * Vault file format v2 (streaming, so big videos never need to fit in RAM):
 *   int MAGIC, UTF name, long plainSize, then chunks [int ctLen][12-byte IV][ciphertext]…, int 0.
 * Every chunk is AES-256-GCM with its chunk index as AAD. Format v1 (single block) is still readable.
 */
internal object VaultCrypto {
    private const val DIR = "vault"
    private const val MAGIC = 0x43564C32
    private const val CHUNK = 1 shl 20
    private const val TAG_BITS = 128
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun dir(c: Context): File = File(c.filesDir, DIR).apply { mkdirs() }

    fun listFiles(c: Context): List<File> =
        dir(c).listFiles()?.filter { it.isFile && it.name.endsWith(".vault") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun newVaultFile(c: Context): File =
        File(dir(c), "${System.currentTimeMillis()}_${SecureRandom().nextInt().toUInt().toString(16)}.vault")

    fun thumbFile(vault: File): File = File(vault.parentFile, vault.nameWithoutExtension + ".thumb")

    fun deleteWithThumb(vault: File) {
        runCatching { vault.delete() }
        runCatching { thumbFile(vault).delete() }
    }

    private fun aad(i: Long): ByteArray = ByteBuffer.allocate(8).putLong(i).array()

    private fun readFull(inp: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = inp.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    fun encrypt(c: Context, input: InputStream, name: String, size: Long, out: File) {
        val key = VaultKeys.get(c)
        val rnd = SecureRandom()
        val fos = FileOutputStream(out)
        DataOutputStream(BufferedOutputStream(fos, 64 * 1024)).use { o ->
            o.writeInt(MAGIC); o.writeUTF(name.take(120)); o.writeLong(size)
            val buf = ByteArray(CHUNK)
            var idx = 0L
            while (true) {
                val n = readFull(input, buf)
                if (n <= 0) break
                val iv = ByteArray(12).also { rnd.nextBytes(it) }
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(aad(idx))
                val ct = cipher.doFinal(buf, 0, n)
                o.writeInt(ct.size); o.write(iv); o.write(ct)
                idx++
                if (n < buf.size) break
            }
            o.writeInt(0)
            o.flush()
            fos.fd.sync()
        }
    }

    /** Decrypts into [sink]; with sink == null it only checks that every authentication tag is valid. */
    fun decryptTo(c: Context, file: File, sink: OutputStream?) {
        val key = VaultKeys.get(c)
        DataInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { inp ->
            val first = inp.readInt()
            if (first == MAGIC) {
                inp.readUTF(); inp.readLong()
                var idx = 0L
                while (true) {
                    val len = inp.readInt()
                    if (len == 0) break
                    require(len in 16..(CHUNK + 16)) { "bad chunk" }
                    val iv = ByteArray(12); inp.readFully(iv)
                    val ct = ByteArray(len); inp.readFully(ct)
                    val cipher = Cipher.getInstance(TRANSFORM)
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                    cipher.updateAAD(aad(idx))
                    sink?.write(cipher.doFinal(ct))
                    idx++
                }
            } else if (first == 12) { // v1 files from the first prototype
                val iv = ByteArray(12); inp.readFully(iv)
                inp.readUTF()
                val size = inp.readInt()
                require(size in 16..(512 * 1024 * 1024)) { "bad size" }
                val enc = ByteArray(size); inp.readFully(enc)
                val cipher = Cipher.getInstance(TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                sink?.write(cipher.doFinal(enc))
            } else {
                error("unknown vault file")
            }
        }
    }

    fun verify(c: Context, file: File): Boolean = runCatching { decryptTo(c, file, null) }.isSuccess

    fun readName(file: File): String =
        DataInputStream(FileInputStream(file)).use { inp ->
            val first = inp.readInt()
            if (first != MAGIC) inp.skipBytes(first)
            inp.readUTF()
        }

    fun saveThumb(c: Context, vault: File, jpeg: ByteArray) {
        encrypt(c, ByteArrayInputStream(jpeg), "thumb", jpeg.size.toLong(), thumbFile(vault))
    }

    fun loadThumb(c: Context, vault: File): Bitmap? {
        val tf = thumbFile(vault)
        if (!tf.exists()) return null
        val bos = ByteArrayOutputStream()
        decryptTo(c, tf, bos)
        val b = bos.toByteArray()
        return BitmapFactory.decodeByteArray(b, 0, b.size)
    }

    /** Decrypts into cacheDir/[dirName] (wiped first). Cache is wiped again whenever the app starts/locks. */
    fun decryptToCache(c: Context, file: File, dirName: String): File {
        val name = safeName(readName(file))
        val d = File(c.cacheDir, dirName).apply { deleteRecursively(); mkdirs() }
        val out = File(d, name)
        FileOutputStream(out).use { decryptTo(c, file, it) }
        return out
    }
}
