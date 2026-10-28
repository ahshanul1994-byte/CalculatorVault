package com.example.calculatorvault

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val unlocked = mutableStateOf(false)

    /** Set right before we open another app/screen on purpose (file picker, installer) so the vault stays open. */
    var suppressLock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent { VaultApp(this, unlocked) }
    }

    override fun onResume() {
        super.onResume()
        suppressLock = false
    }

    // Leaving the app (home, recents, screen off, another app) → lock again.
    override fun onStop() {
        super.onStop()
        if (!suppressLock) lockNow()
    }

    override fun onStart() {
        super.onStart()
        CacheDirs.clear(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        CacheDirs.clear(this)
    }

    private fun lockNow() {
        unlocked.value = false
        VaultKeys.clear()
    }
}

@Composable
private fun VaultApp(activity: MainActivity, unlocked: MutableState<Boolean>) {
    MaterialTheme(
        colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Orange, onBackground = White, onSurface = White)
    ) {
        Surface(Modifier.fillMaxSize(), color = Bg) {
            Box(Modifier.fillMaxSize().background(Bg).systemBarsPadding()) {
                if (unlocked.value) {
                    VaultHost(activity, onLock = { unlocked.value = false; VaultKeys.clear() })
                } else {
                    CalculatorHost(activity, onUnlock = { unlocked.value = true })
                }
            }
        }
    }
}

// ─── Calculator + PIN handling ───────────────────────────────────────────────

@Composable
private fun CalculatorHost(context: Context, onUnlock: () -> Unit) {
    val calc = remember { Calc() }
    var configured by remember { mutableStateOf(PinStore.isConfigured(context)) }
    var firstPin by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }

    CalculatorScreen(
        display = calc.display,
        hint = if (!configured && firstPin == null) "প্রথমবার: ৬ সংখ্যার PIN দিন, তারপর =" else null,
        message = message,
        onKey = { key ->
            message = ""
            if (key != "=") {
                calc.press(key)
            } else {
                val pin = calc.pinCandidate()
                if (pin == null) {
                    calc.equalsKey()
                } else if (!configured) {
                    val f = firstPin
                    if (f == null) {
                        firstPin = pin; calc.reset(); message = "আবার একই PIN দিন, তারপর ="
                    } else if (f == pin) {
                        PinStore.setPin(context, pin)
                        configured = true; firstPin = null; calc.reset(); onUnlock()
                    } else {
                        firstPin = null; calc.reset(); message = "PIN মেলেনি — আবার প্রথম থেকে দিন"
                    }
                } else if (PinStore.verify(context, pin, countFailure = !calc.hasPendingOp())) {
                    calc.reset(); onUnlock()
                } else {
                    // Wrong PIN: behave exactly like a normal calculator.
                    calc.equalsKey(); calc.markEvaluated()
                }
            }
        }
    )
}

// ─── Vault host: permissions, importing, deleting originals, restoring ──────

/** Like OpenMultipleDocuments, but also asks for write access so the original can be deleted. */
private class PickDocs : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, input)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val out = LinkedHashSet<Uri>()
        intent.data?.let { out.add(it) }
        intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { out.add(it) } }
        return out.toList()
    }
}

@Composable
private fun VaultHost(activity: MainActivity, onLock: () -> Unit) {
    val context: Context = activity
    val scope = rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var filter by remember { mutableStateOf<VType?>(null) }
    var restorePending by remember { mutableStateOf<File?>(null) }

    val deleter = remember {
        Deleter(context) { deleted, failed ->
            refresh++
            message = if (failed == 0) {
                "মূল ফাইল ফোন থেকে মুছে গেছে ($deleted টি)। Google Photos ব্যাকআপ চালু থাকলে ক্লাউডের কপি আলাদা মুছুন।"
            } else {
                "Vault-এ যোগ হয়েছে, কিন্তু $failed টি মূল ফাইল মুছতে অনুমতি পাওয়া যায়নি"
            }
        }
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        deleter.onResult(r.resultCode == Activity.RESULT_OK)
    }
    deleter.launcher = deleteLauncher

    fun importMedia(items: List<PickItem>) {
        scope.launch {
            var ok = 0
            var fail = 0
            val toDelete = mutableListOf<Uri>()
            for ((i, item) in items.withIndex()) {
                busy = "Vault-এ যোগ হচ্ছে ${i + 1}/${items.size} …"
                val success = withContext(Dispatchers.IO) { importOne(context, item.uri, item.name, item.type) }
                if (success) { ok++; toDelete.add(item.uri) } else fail++
            }
            busy = null
            refresh++
            message = "$ok টি ফাইল Vault-এ যোগ হয়েছে" + if (fail > 0) " ($fail টি ব্যর্থ)" else ""
            if (toDelete.isNotEmpty()) deleter.start(toDelete)
        }
    }

    fun importDocs(uris: List<Uri>) {
        scope.launch {
            var ok = 0
            var fail = 0
            var notDeleted = 0
            for ((i, u) in uris.withIndex()) {
                busy = "Vault-এ যোগ হচ্ছে ${i + 1}/${uris.size} …"
                val (success, deleted) = withContext(Dispatchers.IO) {
                    val n = queryName(context, u)
                    val s = importOne(context, u, n, typeOf(n))
                    val d = s && runCatching { DocumentsContract.deleteDocument(context.contentResolver, u) }
                        .getOrDefault(false)
                    s to d
                }
                if (success) { ok++; if (!deleted) notDeleted++ } else fail++
            }
            busy = null
            refresh++
            message = "$ok টি ফাইল Vault-এ যোগ হয়েছে" +
                (if (fail > 0) " ($fail টি ব্যর্থ)" else "") +
                (if (notDeleted > 0) "। $notDeleted টির মূল কপি মুছতে পারিনি — ফাইল অ্যাপ থেকে নিজে মুছুন" else "")
        }
    }

    fun doRestore(f: File) {
        scope.launch {
            busy = "ফোনে ফেরত আনা হচ্ছে …"
            val ok = withContext(Dispatchers.IO) {
                restoreToDevice(context, f).also { if (it) VaultCrypto.deleteWithThumb(f) }
            }
            busy = null
            refresh++
            message = if (ok) "ফেরত এসেছে — গ্যালারি/Music/Download এর \"Restored\" ফোল্ডারে দেখুন" else "ফেরত আনা যায়নি"
        }
    }

    val writePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val f = restorePending
        restorePending = null
        if (granted && f != null) doRestore(f) else message = "স্টোরেজ পারমিশন ছাড়া ফেরত আনা যাবে না"
    }

    val readPerms: Array<String> = remember {
        when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )
            Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    val readPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.any { it }) showPicker = true else message = "ছবি/ভিডিও/অডিও দেখতে পারমিশন দরকার"
    }
    val docPicker = rememberLauncherForActivityResult(PickDocs()) { uris ->
        if (uris.isNotEmpty()) importDocs(uris)
    }

    fun openExternal(f: File) {
        scope.launch {
            busy = "খোলা হচ্ছে …"
            val r = withContext(Dispatchers.IO) { runCatching { VaultCrypto.decryptToCache(context, f, "preview") } }
            busy = null
            r.onSuccess { out ->
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeFor(out.name))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    activity.suppressLock = true
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    activity.suppressLock = false
                    Toast.makeText(context, "এই ফাইল খোলার মতো কোনো অ্যাপ নেই", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    activity.suppressLock = false
                    message = "ফাইলটি খোলা যায়নি"
                }
            }.onFailure { message = "ফাইলটি খোলা যায়নি" }
        }
    }

    BackHandler {
        when {
            viewing != null -> viewing = null
            showPicker -> showPicker = false
            else -> onLock()
        }
    }

    val v = viewing
    when {
        v != null -> ViewerScreen(context, v, onClose = { viewing = null })
        showPicker -> PickerScreen(
            context,
            onCancel = { showPicker = false },
            onImport = { items -> showPicker = false; importMedia(items) }
        )
        else -> VaultScreen(
            context = context,
            refresh = refresh,
            message = message,
            filter = filter,
            onFilter = { filter = it },
            onAddMedia = {
                if (readPerms.any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) showPicker = true
                else readPermLauncher.launch(readPerms)
            },
            onAddFiles = {
                activity.suppressLock = true
                docPicker.launch(arrayOf("*/*"))
            },
            onView = { viewing = it },
            onOpenExternal = { openExternal(it) },
            onRestore = { f ->
                if (Build.VERSION.SDK_INT < 29 &&
                    context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    restorePending = f
                    writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else doRestore(f)
            },
            onDelete = { f -> VaultCrypto.deleteWithThumb(f); refresh++; message = "মুছে ফেলা হয়েছে" },
            onChangePin = { pin -> PinStore.setPin(context, pin); message = "PIN বদলানো হয়েছে" },
            onLock = onLock
        )
    }

    busy?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(text) },
            text = { CircularProgressIndicator() }
        )
    }
}
