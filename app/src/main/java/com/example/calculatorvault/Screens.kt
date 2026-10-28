package com.example.calculatorvault

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

internal val Bg = Color(0xFF000000)
internal val Panel = Color(0xFF1C1C1E)
internal val Orange = Color(0xFFFF9F0A)
internal val FnGray = Color(0xFF2C2C2E)
internal val White = Color(0xFFFFFFFF)
private val Red = Color(0xFFFF453A)
private val Scrim = Color(0x99000000)

private fun icon(t: VType) = when (t) {
    VType.IMAGE -> "🖼"; VType.VIDEO -> "🎬"; VType.AUDIO -> "🎵"; VType.APK -> "📦"; VType.OTHER -> "📄"
}

private val FILTERS = listOf<Pair<String, VType?>>(
    "সব" to null, "ছবি" to VType.IMAGE, "ভিডিও" to VType.VIDEO,
    "অডিও" to VType.AUDIO, "APK/অ্যাপ" to VType.APK, "অন্যান্য" to VType.OTHER
)

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Orange else FnGray)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(label, color = if (selected) Color.Black else White, fontSize = 13.sp) }
}

// ─── Calculator ──────────────────────────────────────────────────────────────

@Composable
internal fun CalculatorScreen(
    display: String,
    hint: String?,
    message: String,
    onKey: (String) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 16.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 24.dp, end = 6.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (hint != null) {
                Text(hint, color = Orange.copy(alpha = 0.75f), fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
            }
            Text(
                display, color = White,
                fontSize = if (display.length > 9) 38.sp else 68.sp,
                fontWeight = FontWeight.Thin, textAlign = TextAlign.End, maxLines = 1
            )
            if (message.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(message, color = Orange, fontSize = 13.sp, textAlign = TextAlign.End)
            }
        }
        val rows = listOf(
            listOf("C", "+/−", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("⌫", "0", ".", "=")
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { key ->
                        val isOp = key in listOf("÷", "×", "−", "+", "=")
                        val isFn = key in listOf("C", "+/−", "%", "⌫")
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(if (isOp) Orange else if (isFn) FnGray else Panel, CircleShape)
                                .clickable { onKey(key) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(key, color = if (isOp) Color.Black else White, fontSize = 26.sp)
                        }
                    }
                }
            }
        }
    }
}

// ─── Vault list ──────────────────────────────────────────────────────────────

internal data class VItem(val file: File, val name: String, val type: VType)

@Composable
internal fun VaultScreen(
    context: Context,
    refresh: Int,
    message: String,
    filter: VType?,
    onFilter: (VType?) -> Unit,
    onAddMedia: () -> Unit,
    onAddFiles: () -> Unit,
    onView: (File) -> Unit,
    onOpenExternal: (File) -> Unit,
    onRestore: (File) -> Unit,
    onDelete: (File) -> Unit,
    onChangePin: (String) -> Unit,
    onLock: () -> Unit
) {
    val all = remember(refresh) {
        VaultCrypto.listFiles(context).map { f ->
            val n = runCatching { VaultCrypto.readName(f) }.getOrDefault(f.name)
            VItem(f, n, typeOf(n))
        }
    }
    val shown = remember(all, filter) { if (filter == null) all else all.filter { it.type == filter } }
    var menuFor by remember { mutableStateOf<VItem?>(null) }
    var confirmDelete by remember { mutableStateOf<VItem?>(null) }
    var showPin by remember { mutableStateOf(false) }

    fun open(item: VItem) {
        if (item.type == VType.IMAGE || item.type == VType.VIDEO || item.type == VType.AUDIO) onView(item.file)
        else onOpenExternal(item.file)
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("PRIVATE VAULT", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${all.size} টি ফাইল এনক্রিপ্টেড", color = Color.Gray, fontSize = 13.sp)
            }
            TextButton(onClick = { showPin = true }) { Text("PIN", color = Orange) }
            Button(onClick = onLock, colors = ButtonDefaults.buttonColors(containerColor = FnGray)) {
                Text("LOCK", color = White)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAddMedia, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Orange)
            ) { Text("+ ছবি/ভিডিও/অডিও", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            Button(
                onClick = onAddFiles, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = FnGray)
            ) { Text("+ APK/ফাইল", color = White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = Orange, fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FILTERS) { (label, t) -> Chip(label, filter == t) { onFilter(t) } }
        }
        Spacer(Modifier.height(12.dp))
        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "এখানে কিছু নেই\n\nউপরের বাটন দিয়ে ফাইল যোগ করুন।\nযোগ করলে ফোনের গ্যালারি/ফাইল থেকে মুছে যাবে।",
                    color = Color.Gray, textAlign = TextAlign.Center, lineHeight = 22.sp
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shown, key = { it.file.name }) { item ->
                    VaultTile(context, item, onClick = { open(item) }, onMenu = { menuFor = item })
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Google Photos ব্যাকআপ চালু থাকলে ক্লাউডের কপি Photos অ্যাপ থেকে আলাদা মুছতে হবে।",
            color = Color.Gray, fontSize = 10.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }

    menuFor?.let { item ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            containerColor = Panel,
            title = { Text(item.name, color = White, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = { menuFor = null; open(item) }) { Text("খুলুন / চালান", color = Orange) }
                    TextButton(onClick = { menuFor = null; onRestore(item.file) }) {
                        Text("ফোনে ফেরত নিন (Vault থেকে সরবে)", color = White)
                    }
                    TextButton(onClick = { menuFor = null; confirmDelete = item }) {
                        Text("চিরতরে মুছুন", color = Red)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("বন্ধ", color = White) } }
        )
    }

    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Panel,
            title = { Text("মুছে ফেলবেন?", color = White) },
            text = { Text("${item.name} চিরতরে মুছে যাবে, আর ফেরত পাওয়া যাবে না।", color = Color.Gray) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onDelete(item.file) }) { Text("মুছুন", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("না", color = White) } }
        )
    }

    if (showPin) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        val kb = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        AlertDialog(
            onDismissRequest = { showPin = false },
            containerColor = Panel,
            title = { Text("PIN বদলান", color = White) },
            text = {
                Column {
                    OutlinedTextField(
                        value = p1, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) p1 = it },
                        label = { Text("নতুন ৬ সংখ্যার PIN") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = kb
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = p2, onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) p2 = it },
                        label = { Text("আবার লিখুন") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = kb
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = p1.length == 6 && p1 == p2,
                    onClick = { onChangePin(p1); showPin = false }
                ) { Text("সেভ", color = if (p1.length == 6 && p1 == p2) Orange else Color.Gray) }
            },
            dismissButton = { TextButton(onClick = { showPin = false }) { Text("বাতিল", color = White) } }
        )
    }
}

@Composable
private fun VaultTile(context: Context, item: VItem, onClick: () -> Unit, onMenu: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, item.file) {
        value = withContext(Dispatchers.IO) {
            runCatching { VaultCrypto.loadThumb(context, item.file) }.getOrNull()
        }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .clickable(onClick = onClick)
    ) {
        val t = thumb
        if (t != null) {
            Image(
                bitmap = t.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(icon(item.type), fontSize = 34.sp)
            }
        }
        if (item.type == VType.VIDEO && t != null) {
            Text("▶", color = White, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center))
        }
        Text(
            item.name, color = White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Scrim).padding(4.dp)
        )
        Box(
            Modifier
                .align(Alignment.TopEnd).padding(4.dp).size(28.dp)
                .clip(CircleShape).background(Scrim).clickable(onClick = onMenu),
            contentAlignment = Alignment.Center
        ) { Text("⋮", color = White, fontSize = 16.sp) }
    }
}

// ─── Picker (gallery → vault) ────────────────────────────────────────────────

@Composable
internal fun PickerScreen(context: Context, onCancel: () -> Unit, onImport: (List<PickItem>) -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<PickItem>>(emptyList()) }
    var filter by remember { mutableStateOf<VType?>(null) }
    val selected = remember { mutableStateListOf<PickItem>() }

    LaunchedEffect(Unit) {
        items = withContext(Dispatchers.IO) { queryDeviceMedia(context) }
        loading = false
    }
    val shown = remember(items, filter) { if (filter == null) items else items.filter { it.type == filter } }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("বাতিল", color = Orange) }
            Spacer(Modifier.weight(1f))
            Text(
                if (selected.isEmpty()) "বাছুন" else "${selected.size} টি সিলেক্ট",
                color = White, fontSize = 15.sp
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onImport(selected.toList()) }, enabled = selected.isNotEmpty()) {
                Text("যোগ করুন", color = if (selected.isNotEmpty()) Orange else Color.Gray)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FILTERS.filter { it.second == null || it.second == VType.IMAGE || it.second == VType.VIDEO || it.second == VType.AUDIO }) { (label, t) ->
                    Chip(label, filter == t) { filter = t }
                }
            }
            TextButton(onClick = {
                val allSel = shown.isNotEmpty() && shown.all { it in selected }
                if (allSel) selected.removeAll(shown.toSet()) else shown.forEach { if (it !in selected) selected.add(it) }
            }) { Text("সব", color = Orange) }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Orange)
            }
            shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("কিছু পাওয়া যায়নি", color = Color.Gray)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(shown, key = { it.uri.toString() }) { item ->
                    val isSel = item in selected
                    val thumb by produceState<Bitmap?>(null, item.uri) {
                        value = withContext(Dispatchers.IO) { makeThumbBitmap(context, item.uri, item.type, 200) }
                    }
                    Box(
                        Modifier
                            .aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Panel)
                            .clickable { if (isSel) selected.remove(item) else selected.add(item) }
                    ) {
                        val t = thumb
                        if (t != null) {
                            Image(
                                bitmap = t.asImageBitmap(), contentDescription = item.name,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                            )
                        } else {
                            Column(
                                Modifier.fillMaxSize().padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(icon(item.type), fontSize = 26.sp)
                                Text(item.name, color = Color.Gray, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (item.type == VType.VIDEO) {
                            Text("🎬", fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
                        }
                        Box(
                            Modifier
                                .align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape)
                                .background(if (isSel) Orange else Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center
                        ) { if (isSel) Text("✓", color = Color.Black, fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

// ─── In-app viewer / player ─────────────────────────────────────────────────

private fun decodeImage(context: Context, file: File): Bitmap? {
    val bos = ByteArrayOutputStream()
    VaultCrypto.decryptTo(context, file, bos)
    val bytes = bos.toByteArray()
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var s = 1
    while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        ?: return null
    val deg = try {
        when (ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } catch (_: Exception) { 0f }
    if (deg == 0f) return bmp
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
}

@Composable
internal fun ViewerScreen(context: Context, file: File, onClose: () -> Unit) {
    val activity = context as? Activity
    val name = remember(file) { runCatching { VaultCrypto.readName(file) }.getOrDefault("file") }
    val type = remember(name) { typeOf(name) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var playFile by remember { mutableStateOf<File?>(null) }
    var failed by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            CacheDirs.clear(context)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    LaunchedEffect(file) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                if (type == VType.IMAGE) decodeImage(context, file) to null
                else null to VaultCrypto.decryptToCache(context, file, "play")
            }
        }
        r.onSuccess { (b, f) -> bitmap = b; playFile = f; if (b == null && f == null) failed = true }
            .onFailure { failed = true }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← ফিরুন", color = Orange) }
            Text(
                name, color = White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (type == VType.VIDEO) {
                TextButton(onClick = {
                    landscape = !landscape
                    activity?.requestedOrientation =
                        if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }) { Text("⟳", color = Orange, fontSize = 20.sp) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val b = bitmap
            val pf = playFile
            when {
                failed -> Text("ফাইলটি খোলা যায়নি", color = Color.Gray)
                b != null -> {
                    var scale by remember { mutableFloatStateOf(1f) }
                    var offset by remember { mutableStateOf(Offset.Zero) }
                    Image(
                        bitmap = b.asImageBitmap(), contentDescription = name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 6f)
                                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                                }
                            }
                            .graphicsLayer(
                                scaleX = scale, scaleY = scale,
                                translationX = offset.x, translationY = offset.y
                            )
                    )
                }
                pf != null -> {
                    val isAudio = type == VType.AUDIO
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isAudio) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("🎵", fontSize = 90.sp)
                            }
                        }
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    val mc = MediaController(ctx)
                                    mc.setAnchorView(this)
                                    setMediaController(mc)
                                    setVideoPath(pf.absolutePath)
                                    setOnPreparedListener { it.start() }
                                    setOnErrorListener { _, _, _ -> failed = true; true }
                                }
                            },
                            onRelease = { it.stopPlayback() },
                            modifier = if (isAudio) Modifier.fillMaxWidth().height(110.dp) else Modifier.fillMaxSize()
                        )
                    }
                }
                else -> CircularProgressIndicator(color = Orange)
            }
        }
    }
}
