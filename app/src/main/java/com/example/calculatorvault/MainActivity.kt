package com.example.calculatorvault

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Base64
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.floor

private val Bg      = Color(0xFF000000)
private val Panel   = Color(0xFF1C1C1E)
private val Orange  = Color(0xFFFF9F0A)
private val FnGray  = Color(0xFF2C2C2E)
private val White   = Color(0xFFFFFFFF)

private const val PREFS   = "vault_setup"
private const val PIN_HASH = "pin_hash"
private const val SALT    = "pin_salt"
private const val KEY_B64 = "vault_key"

class MainActivity : ComponentActivity() {
    private val _unlocked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots and recent-app previews
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContent { VaultApp(this, _unlocked) }
    }

    // Auto-lock vault whenever app leaves the foreground
    override fun onStop() {
        super.onStop()
        _unlocked.value = false
    }
}

// ─── Main composable ─────────────────────────────────────────────────────────

@Composable
private fun VaultApp(context: Context, unlockState: MutableState<Boolean>) {
    val prefs       = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var configured  by remember { mutableStateOf(prefs.contains(PIN_HASH)) }
    var unlocked    by unlockState

    // Calculator state
    var display         by remember { mutableStateOf("0") }
    var currentInput    by remember { mutableStateOf("") }
    var firstOperand    by remember { mutableStateOf<Double?>(null) }
    var pendingOp       by remember { mutableStateOf<String?>(null) }
    var waitingSecond   by remember { mutableStateOf(false) }
    var justEvaluated   by remember { mutableStateOf(false) }

    var message by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    // Holds uris still waiting for an original-file delete attempt (used across
    // the two possible permission/launcher round-trips below).
    var pendingDelete by remember { mutableStateOf<List<Uri>>(emptyList()) }

    // Launcher for the Android 11+ system "allow delete?" dialog AND the
    // Android 10 per-item RecoverableSecurityException confirmation.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        refresh++
        if (result.resultCode != Activity.RESULT_OK) {
            message = "Vault-এ কপি হয়েছে, কিন্তু অরিজিনাল ফাইল গ্যালারি থেকে মুছতে অনুমতি দেওয়া হয়নি"
        }
    }

    // Android 6–9 (API 23–28) needs a runtime WRITE_EXTERNAL_STORAGE grant
    // before the originals can be deleted via ContentResolver.
    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingDelete.isNotEmpty()) {
            deleteOriginals(context, pendingDelete, deleteLauncher)
        } else if (!granted) {
            message = "Vault-এ কপি হয়েছে, কিন্তু স্টোরেজ পারমিশন না থাকায় গ্যালারি থেকে অরিজিনাল ফাইল মুছতে পারিনি"
        }
        pendingDelete = emptyList()
    }

    // File picker
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        var imported = 0
        val toDelete = mutableListOf<Uri>()
        uris.forEach { uri ->
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    VaultCrypto.encryptToFile(context, safeName(uri.lastPathSegment ?: "file"), bytes)
                    imported++
                    toDelete.add(uri)
                }
            } catch (_: Exception) { }
        }
        if (toDelete.isNotEmpty()) {
            val needsRuntimePermission =
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                    PackageManager.PERMISSION_GRANTED
            if (needsRuntimePermission) {
                pendingDelete = toDelete
                writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                deleteOriginals(context, toDelete, deleteLauncher)
            }
        }
        message = "$imported টি ফাইল Vault-এ যোগ হয়েছে"
        refresh++
    }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Bg, surface = Panel, primary = Orange,
        onBackground = White, onSurface = White
    )) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            if (unlocked) {
                VaultScreen(
                    context  = context,
                    refresh  = refresh,
                    message  = message,
                    onRefresh = { refresh++ },
                    onAdd    = {
                        picker.launch(arrayOf(
                            "image/*", "video/*",
                            "application/vnd.android.package-archive"
                        ))
                    },
                    onLock   = {
                        unlocked = false
                        currentInput = ""; display = "0"
                        firstOperand = null; pendingOp = null
                        waitingSecond = false; justEvaluated = false
                    }
                )
            } else {
                CalculatorScreen(
                    display  = display,
                    isSetup  = !configured,
                    message  = message,
                    onKey    = { key ->
                        message = ""
                        handleKey(
                            key, context, prefs, configured,
                            display, currentInput,
                            firstOperand, pendingOp,
                            waitingSecond, justEvaluated,
                            onDisplayChange     = { display = it },
                            onInputChange       = { currentInput = it },
                            onFirstOpChange     = { firstOperand = it },
                            onPendingOpChange   = { pendingOp = it },
                            onWaitingChange     = { waitingSecond = it },
                            onEvaluatedChange   = { justEvaluated = it },
                            onMessage           = { message = it },
                            onConfigured        = { configured = it },
                            onUnlock            = { unlocked = it }
                        )
                    }
                )
            }
        }
    }
}

// ─── Key handler (extracted to keep composable clean) ────────────────────────

private fun handleKey(
    key: String,
    context: Context,
    prefs: android.content.SharedPreferences,
    configured: Boolean,
    display: String,
    currentInput: String,
    firstOperand: Double?,
    pendingOp: String?,
    waitingSecond: Boolean,
    justEvaluated: Boolean,
    onDisplayChange: (String) -> Unit,
    onInputChange: (String) -> Unit,
    onFirstOpChange: (Double?) -> Unit,
    onPendingOpChange: (String?) -> Unit,
    onWaitingChange: (Boolean) -> Unit,
    onEvaluatedChange: (Boolean) -> Unit,
    onMessage: (String) -> Unit,
    onConfigured: (Boolean) -> Unit,
    onUnlock: (Boolean) -> Unit
) {
    fun setNum(v: String) { onInputChange(v); onDisplayChange(v.ifEmpty { "0" }) }
    fun reset() {
        onInputChange(""); onDisplayChange("0")
        onFirstOpChange(null); onPendingOpChange(null)
        onWaitingChange(false); onEvaluatedChange(false)
    }

    when (key) {
        "C" -> reset()

        "⌫" -> {
            if (!justEvaluated && currentInput.isNotEmpty()) {
                val s = currentInput.dropLast(1)
                onInputChange(s); onDisplayChange(s.ifEmpty { "0" })
            } else {
                onInputChange(""); onDisplayChange("0"); onEvaluatedChange(false)
            }
        }

        "+/−" -> {
            currentInput.toDoubleOrNull()?.let {
                val s = formatNum(-it); onInputChange(s); onDisplayChange(s)
            }
            onEvaluatedChange(false)
        }

        "%" -> {
            currentInput.toDoubleOrNull()?.let {
                val s = formatNum(it / 100.0); onInputChange(s); onDisplayChange(s)
            }
            onEvaluatedChange(false)
        }

        "÷", "×", "−", "+" -> {
            val v = currentInput.toDoubleOrNull() ?: display.toDoubleOrNull() ?: 0.0
            if (firstOperand != null && !waitingSecond && pendingOp != null) {
                val r = calc(firstOperand!!, pendingOp!!, v)
                onFirstOpChange(r); onDisplayChange(formatNum(r))
            } else {
                onFirstOpChange(v)
            }
            onPendingOpChange(key)
            onWaitingChange(true)
            onInputChange("")
            onEvaluatedChange(false)
        }

        "." -> {
            if (justEvaluated) { onInputChange("0."); onDisplayChange("0."); onEvaluatedChange(false) }
            else if (!currentInput.contains('.')) {
                val s = if (currentInput.isEmpty()) "0." else "$currentInput."
                onInputChange(s); onDisplayChange(s)
            }
        }

        "=" -> {
            // If current input is exactly 6 digits → treat as PIN attempt
            if (currentInput.all { it.isDigit() } && currentInput.length == 6) {
                if (!configured) {
                    setupPin(prefs, currentInput)
                    onConfigured(true); onUnlock(true)
                    reset(); onMessage("PIN সেট হয়েছে")
                } else {
                    val salt     = Base64.decode(prefs.getString(SALT, ""), Base64.NO_WRAP)
                    val expected = Base64.decode(prefs.getString(PIN_HASH, ""), Base64.NO_WRAP)
                    if (PinSecurity.verify(currentInput, salt, expected)) {
                        onUnlock(true); reset()
                    } else {
                        // Wrong PIN → silently show calculator result
                        if (firstOperand != null && pendingOp != null) {
                            val v = currentInput.toDoubleOrNull() ?: 0.0
                            val r = calc(firstOperand!!, pendingOp!!, v)
                            val s = formatNum(r)
                            onDisplayChange(s); onInputChange(s)
                            onFirstOpChange(null); onPendingOpChange(null)
                        }
                        onEvaluatedChange(true)
                        onWaitingChange(false)
                    }
                }
            } else {
                // Normal calculation
                if (firstOperand != null && pendingOp != null) {
                    val v = currentInput.toDoubleOrNull() ?: firstOperand!!
                    val r = calc(firstOperand!!, pendingOp!!, v)
                    val s = formatNum(r)
                    onDisplayChange(s); onInputChange(s)
                    onFirstOpChange(null); onPendingOpChange(null)
                    onWaitingChange(false); onEvaluatedChange(true)
                }
            }
        }

        else -> { // digit
            if (key.length == 1 && key[0].isDigit()) {
                val s = when {
                    justEvaluated || waitingSecond -> key
                    currentInput == "0"            -> key
                    currentInput.length >= 12      -> return
                    else                           -> currentInput + key
                }
                onWaitingChange(false); onEvaluatedChange(false)
                onInputChange(s); onDisplayChange(s)
            }
        }
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

private fun calc(a: Double, op: String, b: Double): Double = when (op) {
    "+" -> a + b
    "−" -> a - b
    "×" -> a * b
    "÷" -> if (b != 0.0) a / b else Double.NaN
    else -> b
}

private fun formatNum(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return "Error"
    return if (v == floor(v) && abs(v) < 1e12) v.toLong().toString()
    else "%.10g".format(v).trimEnd('0').trimEnd('.')
}

private fun safeName(raw: String): String =
    raw.substringAfterLast('/').substringAfterLast(':')
        .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "vault_file" }

private fun setupPin(prefs: android.content.SharedPreferences, pin: String) {
    val salt     = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
    prefs.edit()
        .putString(PIN_HASH, Base64.encodeToString(PinSecurity.hash(pin, salt), Base64.NO_WRAP))
        .putString(SALT,     Base64.encodeToString(salt, Base64.NO_WRAP))
        .putString(KEY_B64,  Base64.encodeToString(keyBytes, Base64.NO_WRAP))
        .apply()
}

private fun deleteOriginals(
    context: Context,
    uris: List<Uri>,
    launcher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Android 11+: convert document URIs → MediaStore URIs, then show the
        // single system "allow delete?" dialog for all of them at once.
        val mediaUris = uris.mapNotNull { uri ->
            try { android.provider.MediaStore.getMediaUri(context, uri) } catch (_: Exception) { null }
        }
        if (mediaUris.isNotEmpty()) {
            try {
                val req = android.provider.MediaStore.createDeleteRequest(context.contentResolver, mediaUris)
                launcher.launch(IntentSenderRequest.Builder(req.intentSender).build())
                return
            } catch (_: Exception) { /* fall through to direct delete below */ }
        }
    }
    deleteDirect(context, uris, launcher)
}

/**
 * Direct deletion path for Android ≤10 (and as a fallback for R+ when the
 * batch delete request couldn't be built).
 *
 * On Android 10 (Q), ContentResolver.delete() on a media item the app didn't
 * create throws RecoverableSecurityException instead of just failing — that
 * exception carries an IntentSender that shows the user a one-time "allow
 * delete?" prompt. Previously this was being swallowed by a blanket
 * catch-all, so the prompt never appeared and the original file silently
 * stayed in the gallery. Each such prompt is launched in turn (the system
 * only allows one outstanding IntentSender launch at a time).
 */
private fun deleteDirect(
    context: Context,
    uris: List<Uri>,
    launcher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>
) {
    for (uri in uris) {
        try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows <= 0) {
                // Some providers return 0 instead of throwing; try SAF deleteDocument too.
                try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (_: Exception) { }
            }
        } catch (e: RecoverableSecurityException) {
            try {
                val sender = e.userAction.actionIntent.intentSender
                launcher.launch(IntentSenderRequest.Builder(sender).build())
            } catch (_: Exception) { }
        } catch (_: Exception) {
            try { DocumentsContract.deleteDocument(context.contentResolver, uri) } catch (_: Exception) { }
        }
    }
}

// ─── UI: Calculator ───────────────────────────────────────────────────────────

@Composable
private fun CalculatorScreen(
    display: String,
    isSetup: Boolean,
    message: String,
    onKey: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 16.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Display area
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 52.dp, end = 6.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (isSetup) {
                Text(
                    "প্রথমবার: ৬ সংখ্যার PIN দিন, তারপর =",
                    color = Orange.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = display,
                color = White,
                fontSize = if (display.length > 9) 38.sp else 68.sp,
                fontWeight = FontWeight.Thin,
                textAlign = TextAlign.End,
                maxLines = 1
            )
            if (message.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(message, color = Orange, fontSize = 13.sp, textAlign = TextAlign.End)
            }
        }

        // Button grid
        val rows = listOf(
            listOf("C", "+/−", "%", "÷"),
            listOf("7", "8",   "9", "×"),
            listOf("4", "5",   "6", "−"),
            listOf("1", "2",   "3", "+"),
            listOf("⌫", "0",   ".", "=")
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { key ->
                        val isOp = key in listOf("÷", "×", "−", "+", "=")
                        val isFn = key in listOf("C", "+/−", "%", "⌫")
                        val bg   = when { isOp -> Orange; isFn -> FnGray; else -> Panel }
                        val fg   = if (isOp) Color.Black else White
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(bg, CircleShape)
                                .clickable { onKey(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text       = key,
                                color      = fg,
                                fontSize   = 26.sp,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── UI: Vault ────────────────────────────────────────────────────────────────

@Composable
private fun VaultScreen(
    context: Context,
    refresh: Int,
    message: String,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onLock: () -> Unit
) {
    val files    = remember(refresh) { VaultCrypto.listFiles(context) }
    var selected by remember { mutableStateOf<File?>(null) }

    Column(Modifier.fillMaxSize().background(Bg).padding(18.dp)) {
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PRIVATE VAULT", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${files.size} টি ফাইল এনক্রিপ্টেড", color = Color.Gray, fontSize = 13.sp)
            }
            Button(
                onClick = onLock,
                colors  = ButtonDefaults.buttonColors(containerColor = FnGray)
            ) { Text("LOCK", color = White) }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick   = onAdd,
            modifier  = Modifier.fillMaxWidth(),
            colors    = ButtonDefaults.buttonColors(containerColor = Orange)
        ) {
            Text("+ ফাইল যোগ করুন (গ্যালারি থেকে)", color = Color.Black, fontWeight = FontWeight.Bold)
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = Orange, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "এখনো কোনো ফাইল নেই\n\nউপরে \"+ ফাইল যোগ করুন\" চাপুন\nছবি/ভিডিও বেছে নিলে Vault-এ যোগ হবে\nএবং গ্যালারি থেকে সরে যাবে",
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement   = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(files) { file ->
                    val displayName = remember(file) {
                        runCatching { VaultCrypto.readOriginalName(file) }.getOrDefault(file.name)
                    }
                    Column(
                        Modifier
                            .background(Panel, RoundedCornerShape(14.dp))
                            .clickable { selected = file }
                            .padding(16.dp)
                    ) {
                        Text("\uD83D\uDD12", fontSize = 32.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            displayName.take(30),
                            color = White, fontSize = 12.sp, maxLines = 2
                        )
                        Text("${file.length() / 1024} KB", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    selected?.let { f ->
        val displayName = remember(f) {
            runCatching { VaultCrypto.readOriginalName(f) }.getOrDefault(f.name)
        }
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor   = Panel,
            title = { Text("Vault ফাইল", color = White) },
            text  = { Text(displayName, color = Color.Gray) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        openVaultFile(context, f)
                        selected = null
                    }) { Text("খুলুন", color = Orange) }
                    TextButton(onClick = {
                        runCatching { f.delete() }
                        selected = null; onRefresh()
                    }) { Text("মুছুন", color = Color(0xFFFF453A)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text("বন্ধ", color = White) }
            }
        )
    }
}

// ─── Security ────────────────────────────────────────────────────────────────

private object PinSecurity {
    fun hash(pin: String, salt: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").also { it.update(salt) }
            .digest(pin.toByteArray(Charsets.UTF_8))

    fun verify(pin: String, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(pin, salt), expected)
}

private object VaultCrypto {
    private const val DIR            = "vault"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS       = 128

    private fun getKey(context: Context): javax.crypto.SecretKey {
        val prefs   = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = prefs.getString(KEY_B64, null) ?: error("Vault key missing")
        return SecretKeySpec(Base64.decode(encoded, Base64.NO_WRAP), "AES")
    }

    fun listFiles(context: Context): List<File> =
        File(context.filesDir, DIR).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".vault") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun encryptToFile(context: Context, originalName: String, data: ByteArray) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val id  = "${System.currentTimeMillis()}_${originalName.hashCode().toUInt().toString(16)}"
        val file = File(dir, "$id.vault")
        val iv   = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey(context), GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(data)
        val fos = FileOutputStream(file)
        DataOutputStream(fos).use { out ->
            out.writeInt(iv.size); out.write(iv)
            out.writeUTF(originalName.take(120))
            out.writeInt(encrypted.size); out.write(encrypted)
            out.flush()
        }
        fos.fd.sync()
    }

    fun decryptFile(context: Context, file: File): ByteArray {
        DataInputStream(FileInputStream(file)).use { inp ->
            val ivLen = inp.readInt(); require(ivLen == 12) { "Invalid IV" }
            val iv = ByteArray(ivLen); inp.readFully(iv)
            inp.readUTF()
            val size = inp.readInt(); require(size in 16..(512 * 1024 * 1024))
            val enc = ByteArray(size); inp.readFully(enc)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getKey(context), GCMParameterSpec(TAG_BITS, iv))
            return cipher.doFinal(enc)
        }
    }

    /** Reads just the stored original file name, without decrypting the content. */
    fun readOriginalName(file: File): String {
        DataInputStream(FileInputStream(file)).use { inp ->
            val ivLen = inp.readInt()
            inp.skipBytes(ivLen)
            return inp.readUTF()
        }
    }

    /**
     * Decrypts [file] into a private cache folder (cacheDir/preview) so it can be
     * opened in an external viewer via FileProvider. Old cached previews are
     * cleared first so decrypted plaintext never lingers on disk.
     * Returns the decrypted File plus its original (readable) file name.
     */
    fun decryptToCache(context: Context, file: File): Pair<File, String> {
        val originalName = readOriginalName(file)
        val data = decryptFile(context, file)
        val cacheDir = File(context.cacheDir, "preview").apply { mkdirs() }
        cacheDir.listFiles()?.forEach { it.delete() }
        val outFile = File(cacheDir, safeName(originalName))
        FileOutputStream(outFile).use { it.write(data) }
        return outFile to originalName
    }
}

private fun mimeTypeFor(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}

private fun openVaultFile(context: Context, file: File) {
    try {
        val (decrypted, originalName) = VaultCrypto.decryptToCache(context, file)
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", decrypted
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeFor(originalName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "ফাইলটি খোলা যায়নি — এটি খোলার মতো কোনো অ্যাপ নেই হয়তো", Toast.LENGTH_SHORT).show()
    }
}
