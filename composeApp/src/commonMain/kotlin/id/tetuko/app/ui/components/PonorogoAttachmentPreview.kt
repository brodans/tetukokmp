package id.tetuko.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import id.tetuko.app.service.AppServices
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

@OptIn(ExperimentalEncodingApi::class)
@Composable
fun PonorogoAttachmentPreview(source: String, description: String) {
    val model = remember(source) {
        when {
            source.startsWith("data:image/", ignoreCase = true) -> source
            source.startsWith("/9j/") || source.startsWith("iVBOR") ->
                runCatching { Base64.decode(source) }.getOrNull()
            else -> AppServices.ponorogoClient.imageProxyUrl(source)
        }
    }
    if (model == null) return

    var expanded by remember(source) { mutableStateOf(false) }
    var failed by remember(source) { mutableStateOf(false) }

    if (!failed) {
        AsyncImage(
            model = model,
            contentDescription = description,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = true },
            contentScale = ContentScale.Crop,
            onError = { failed = true },
        )
    } else {
        Text("Foto tidak dapat dimuat", style = MaterialTheme.typography.labelSmall)
    }

    if (expanded) {
        FullscreenImageViewer(
            model = model,
            title = description,
            onDismiss = { expanded = false },
            onDownload = { AppServices.ponorogoClient.fetchImage(source) },
            downloadFileName = sourceImageFileName(source),
        )
    }
}

private fun sourceImageFileName(source: String): String? {
    val mimeExtension = Regex("^data:image/([^;,]+)", RegexOption.IGNORE_CASE)
        .find(source)?.groupValues?.getOrNull(1)?.lowercase()
        ?.let { if (it == "jpeg") "jpg" else it }
        ?.takeIf { it in setOf("jpg", "png", "webp") }
    if (mimeExtension != null) return "lampiran.$mimeExtension"
    if (source.startsWith("iVBOR")) return "lampiran.png"
    if (source.startsWith("/9j/")) return "lampiran.jpg"
    return source.substringBefore('?').substringAfterLast('/').takeIf { it.contains('.') }
}

@Composable
fun FullscreenImageViewer(
    model: Any,
    title: String,
    onDismiss: () -> Unit,
    onDownload: (suspend () -> ByteArray)? = null,
    downloadFileName: String? = null,
) {
    var loadingImage by remember(model) { mutableStateOf(true) }
    var saving by remember(model) { mutableStateOf(false) }
    var failed by remember(model) { mutableStateOf(false) }
    var zoom by remember(model) { mutableFloatStateOf(1f) }
    var pan by remember(model) { mutableStateOf(Offset.Zero) }
    var downloadMessage by remember(model) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xF20B1120))) {
            AsyncImage(
                model = model,
                contentDescription = title,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(model) {
                        detectTransformGestures { _, panChange, zoomChange, _ ->
                            zoom = (zoom * zoomChange).coerceIn(1f, 4f)
                            pan = if (zoom <= 1f) Offset.Zero else pan + panChange
                        }
                    }
                    .pointerInput(model) {
                        detectTapGestures(onDoubleTap = {
                            if (zoom > 1f) {
                                zoom = 1f
                                pan = Offset.Zero
                            } else zoom = 2.5f
                        })
                    }
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    },
                contentScale = ContentScale.Fit,
                onLoading = { loadingImage = true; failed = false },
                onSuccess = { loadingImage = false; failed = false },
                onError = { loadingImage = false; failed = true },
            )

            Surface(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                color = Color(0xCC0B1120),
                shape = RoundedCornerShape(0.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        modifier = Modifier.weight(1f),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = { zoom = (zoom / 1.25f).coerceIn(1f, 4f); if (zoom <= 1f) pan = Offset.Zero }) {
                        Icon(Icons.Filled.ZoomOut, "Perkecil gambar", tint = Color.White)
                    }
                    IconButton(onClick = { zoom = (zoom * 1.25f).coerceIn(1f, 4f) }) {
                        Icon(Icons.Filled.ZoomIn, "Perbesar gambar", tint = Color.White)
                    }
                    IconButton(onClick = { zoom = 1f; pan = Offset.Zero }) {
                        Icon(Icons.Filled.RestartAlt, "Reset zoom", tint = Color.White)
                    }
                    if (onDownload != null) {
                        IconButton(
                            enabled = !saving,
                            onClick = {
                                scope.launch {
                                    saving = true
                                    downloadMessage = null
                                    runCatching { onDownload() }
                                        .onSuccess { bytes ->
                                            val name = downloadFileName?.takeIf { it.isNotBlank() }
                                                ?: "${title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "lampiran" }}.jpg"
                                            val saved = AppServices.binaryExporter?.invoke(name, bytes) ?: false
                                            downloadMessage = if (saved) "Gambar berhasil disimpan." else "Simpan dibatalkan atau tidak tersedia."
                                        }
                                        .onFailure { downloadMessage = it.message ?: "Gagal menyimpan gambar." }
                                    saving = false
                                }
                            },
                        ) { Icon(Icons.Filled.Download, "Simpan gambar", tint = Color.White) }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, "Tutup viewer", tint = Color.White)
                    }
                }
            }

            if (loadingImage || saving) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            if (failed) {
                Text(
                    "Gambar tidak dapat dimuat.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Filled.ZoomIn, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("${(zoom * 100).roundToInt()}%", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                downloadMessage?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(it, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
