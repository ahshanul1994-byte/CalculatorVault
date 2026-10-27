package com.example.calculatorvault

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import android.widget.Toast
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

private val Bg = Color(0xFF090B10)
private val Panel = Color(0xFF171B24)
private val Orange = Color(0xFFFF9F0A)
private val White = Color(0xFFF7F7F7)
private const val PREFS = "vault_setup"
private const val PIN_HASH = "pin_hash"
private const val SALT = "pin_salt"
private const val KEY_B64 = "vault_key"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep sensitive content out of screenshots and recent-app previews.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContent { VaultApp(this) }
    }
}

@Composable
private fun VaultApp(context: Context) {
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var configured by remember { mutableStateOf(prefs.contains(PIN_HASH)) }
    var unlocked by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("0") }
    var message by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        var imported = 0
        uris.forEach { uri ->
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    val name = safeName(uri.lastPathSegment ?: "vault_file")
                    VaultCrypto.encryptToFile(context, name, bytes)
                    imported++
                }
            } catch (_: Exception) { }
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
                    context = context,
                    refresh = refresh,
                    onRefresh = { refresh++ },
                    onAdd = { picker.launch(arrayOf("image/*", "video/*", "application/vnd.android.package-archive")) },
                    onLock = { unlocked = false; pin = ""; display = "0" },
                    message = message
                )
            } else {
                CalculatorScreen(
                    display = display,
                    pinLength = pin.length,
                    isSetup = !configured,
                    message = message,
                    onKey = { key ->
                        message = ""
                        when (key) {
                            "C" -> { pin = ""; display = "0" }
                            "⌫" -> {
                                if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                display = if (pin.isEmpty()) "0" else "•".repeat(pin.length)
                            }
                            "=" -> {
                                if (pin.length != 6) {
                                    message = "৬ ডিজিটের PIN লিখুন"
                                } else if (!configured) {
                                    val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                                    val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                                    val hash = PinSecurity.hash(pin, salt)
                                    prefs.edit()
                                        .putString(PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                                        .putString(SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                                        .putString(KEY_B64, Base64.encodeToString(keyBytes, Base64.NO_WRAP))
                                        .apply()
                                    configured = true
                                    unlocked = true
                                    pin = ""
                                    display = "0"
                                    message = "PIN সেট হয়েছে"
                                } else {
                                    val salt = Base64.decode(prefs.getString(SALT, ""), Base64.NO_WRAP)
                                    val expected = Base64.decode(prefs.getString(PIN_HASH, ""), Base64.NO_WRAP)
                                    if (PinSecurity.verify(pin, salt, expected)) {
                                        unlocked = true
                                        pin = ""
                                        display = "0"
                                        message = ""
                                    } else {
                                        pin = ""
                                        display = "0"
                                        message = "ভুল PIN"
                                    }
                                }
                            }
                            in listOf("+", "−", "×", "÷", "%", "+/−", ".") -> {
                                message = "Vault খুলতে ৬ ডিজিটের PIN লিখে = চাপুন"
                            }
                            else -> {
                                if (key.length == 1 && key[0].isDigit() && pin.length < 6) {
                                    pin += key
                                    display = "•".repeat(pin.length)
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CalculatorScreen(
    display: String,
    pinLength: Int,
    isSetup: Boolean,
    message: String,
    onKey: (String) -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Bg).padding(horizontal = 18.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text("CALCULATOR", color = Color.Gray, fontSize = 12.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(10.dp))
            Text(
                if (display == "0") "0" else display,
                color = White, fontSize = 42.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isSetup) "প্রথমে ৬ ডিজিটের PIN সেট করুন" else "সাধারণ হিসাব • ব্যক্তিগত মোড",
                color = Color.Gray, fontSize = 12.sp
            )
            if (message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(message, color = Orange, fontSize = 13.sp)
            }
        }
        val rows = listOf(
            listOf("C", "+/−", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("⌫", "0", ".", "=")
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { key ->
                        val special = key in listOf("÷", "×", "−", "+", "=")
                        Box(
                            Modifier.weight(1f).height(66.dp)
                                .background(if (special) Orange else Panel, RoundedCornerShape(22.dp))
                                .clickable { onKey(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(key, color = if (special) Color.Black else White,
                                fontSize = 24.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        Text("Vault access: 6-digit PIN + =", color = Color.DarkGray, fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun VaultScreen(
    context: Context,
    refresh: Int,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onLock: () -> Unit,
    message: String
) {
    val files = remember(refresh) { VaultCrypto.listFiles(context) }
    var selected by remember { mutableStateOf<File?>(null) }
    var previewText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Bg).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PRIVATE VAULT", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("${files.size} টি এনক্রিপ্টেড ফাইল", color = Color.Gray, fontSize = 13.sp)
            }
            TextButton(onClick = onLock) { Text("LOCK", color = Orange) }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f)) { Text("+ ফাইল যোগ") }
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp)); Text(message, color = Orange, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("এখনো কোনো ছবি বা ভিডিও নেই\n“ফাইল যোগ” চাপুন", color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(files) { file ->
                    Column(Modifier.background(Panel, RoundedCornerShape(14.dp)).clickable {
                        selected = file
                        previewText = try {
                            val bytes = VaultCrypto.decryptFile(context, file)
                            val temp = File(context.cacheDir, "vault_preview_${System.currentTimeMillis()}_${safeName(file.name)}")
                            temp.writeBytes(bytes)
                            "ডিক্রিপ্ট করা ফাইল সাময়িকভাবে ক্যাশে রাখা হয়েছে:\n${temp.name}\n\nএই starter build-এ ইন-অ্যাপ ছবি/ভিডিও প্রিভিউ নেই। ফাইলটি Export/Restore ফিচার পরবর্তী ধাপে যোগ করা হবে।"
                        } catch (_: Exception) { "ফাইল খোলা যায়নি" }
                    }.padding(14.dp)) {
                        Text("🔒", fontSize = 30.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(file.name.removeSuffix(".vault"), color = White, maxLines = 2, fontSize = 13.sp)
                        Text("${file.length() / 1024} KB encrypted", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
        }
    }
    if (selected != null) {
        AlertDialog(
            onDismissRequest = { selected = null; previewText = "" },
            title = { Text("Vault ফাইল") },
            text = { Text(previewText) },
            confirmButton = {
                TextButton(onClick = {
                    selected?.let { runCatching { it.delete() } }
                    selected = null; previewText = ""; onRefresh()
                }) { Text("ফাইল মুছুন") }
            },
            dismissButton = {
                TextButton(onClick = { selected = null; previewText = "" }) { Text("বন্ধ") }
            }
        )
    }
}

private fun safeName(raw: String): String =
    raw.substringAfterLast('/').substringAfterLast(':')
        .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "vault_file" }

private object PinSecurity {
    fun hash(pin: String, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(pin.toByteArray(Charsets.UTF_8))
    }
    fun verify(pin: String, salt: ByteArray, expected: ByteArray): Boolean {
        return MessageDigest.isEqual(hash(pin, salt), expected)
    }
}

private object VaultCrypto {
    private const val DIR = "vault"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun getKey(context: Context): SecretKey {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encoded = prefs.getString(KEY_B64, null) ?: error("Vault key missing")
        return SecretKeySpec(Base64.decode(encoded, Base64.NO_WRAP), "AES")
    }

    fun listFiles(context: Context): List<File> =
        File(context.filesDir, DIR).listFiles()?.filter { it.isFile && it.name.endsWith(".vault") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun encryptToFile(context: Context, originalName: String, data: ByteArray) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val id = "${System.currentTimeMillis()}_${originalName.hashCode().toUInt().toString(16)}"
        val file = File(dir, "$id.vault")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey(context), GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(data)
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(iv.size)
            out.write(iv)
            out.writeUTF(originalName.take(120))
            out.writeInt(encrypted.size)
            out.write(encrypted)
            out.fd.sync()
        }
    }

    fun decryptFile(context: Context, file: File): ByteArray {
        DataInputStream(FileInputStream(file)).use { input ->
            val ivLength = input.readInt()
            require(ivLength == 12) { "Invalid IV" }
            val iv = ByteArray(ivLength); input.readFully(iv)
            input.readUTF() // Original filename metadata
            val size = input.readInt()
            require(size in 16..(512 * 1024 * 1024)) { "Invalid file size" }
            val encrypted = ByteArray(size); input.readFully(encrypted)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getKey(context), GCMParameterSpec(TAG_BITS, iv))
            return cipher.doFinal(encrypted)
        }
    }
}
