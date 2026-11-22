@file:OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)

package id.tetuko.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import id.tetuko.app.service.AppServices
import id.tetuko.app.service.ImageCropRect
import id.tetuko.app.service.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ImageCropDialog(
    file: PickedFile,
    onDismiss: () -> Unit,
    onCropped: (PickedFile) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val canvasSizePx = with(density) { 300.dp.toPx() }
    val cropSizePx = canvasSizePx * 0.74f
    var image by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var zoom by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    var busy by remember(file) { mutableStateOf(false) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(file) {
        image = runCatching { file.bytes.decodeToImageBitmap() }.getOrNull()
        if (image == null) error = "Format foto tidak dapat dibaca. Pilih JPG atau PNG lain."
    }

    val bitmap = image
    val minimumZoom = bitmap?.let {
        val baseScale = min(canvasSizePx / it.width, canvasSizePx / it.height)
        (cropSizePx / (min(it.width, it.height) * baseScale)).coerceIn(1f, 3f)
    } ?: 1f

    fun applyCrop() {
        val source = bitmap ?: return
        val cropper = AppServices.imageCropper
        if (cropper == null) {
            error = "Pemroses crop foto tidak tersedia di platform ini."
            return
        }
        val scale = min(canvasSizePx / source.width, canvasSizePx / source.height) * zoom
        val sourceSide = cropSizePx / scale
        val imageLeft = (canvasSizePx - source.width * scale) / 2f + offset.x
        val imageTop = (canvasSizePx - source.height * scale) / 2f + offset.y
        val left = ((canvasSizePx - cropSizePx) / 2f - imageLeft) / scale
        val top = ((canvasSizePx - cropSizePx) / 2f - imageTop) / scale
        val safeSide = min(sourceSide, min(source.width.toFloat(), source.height.toFloat()))
        val rect = ImageCropRect(
            left = (left.coerceIn(0f, source.width - safeSide) / source.width).toDouble(),
            top = (top.coerceIn(0f, source.height - safeSide) / source.height).toDouble(),
            width = (safeSide / source.width).toDouble(),
            height = (safeSide / source.height).toDouble(),
        )
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { cropper(file, rect) }.getOrNull() }
            busy = false
            if (result == null) error = "Foto gagal diproses. Coba pilih berkas gambar lain."
            else onCropped(result)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Sesuaikan Foto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Geser foto untuk mengatur posisi, lalu sesuaikan zoom.", style = MaterialTheme.typography.bodySmall)
                if (bitmap != null) {
                    Canvas(
                        Modifier.size(300.dp).pointerInput(bitmap, minimumZoom) {
                            detectTransformGestures { _, pan, zoomChange, _ ->
                                val nextZoom = (zoom * zoomChange).coerceIn(minimumZoom, 3f)
                                zoom = nextZoom
                                val scale = min(canvasSizePx / bitmap.width, canvasSizePx / bitmap.height) * nextZoom
                                val maxX = max(0f, (bitmap.width * scale - cropSizePx) / 2f)
                                val maxY = max(0f, (bitmap.height * scale - cropSizePx) / 2f)
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    y = (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            }
                        },
                    ) {
                        drawRect(Color.Black)
                        val scale = min(size.width / bitmap.width, size.height / bitmap.height) * zoom
                        val imageWidth = bitmap.width * scale
                        val imageHeight = bitmap.height * scale
                        val imageLeft = (size.width - imageWidth) / 2f + offset.x
                        val imageTop = (size.height - imageHeight) / 2f + offset.y
                        drawImage(
                            image = bitmap,
                            dstOffset = IntOffset(imageLeft.roundToInt(), imageTop.roundToInt()),
                            dstSize = IntSize(imageWidth.roundToInt(), imageHeight.roundToInt()),
                        )
                        val cropSize = size.minDimension * 0.74f
                        val cropLeft = (size.width - cropSize) / 2f
                        val cropTop = (size.height - cropSize) / 2f
                        val shade = Color.Black.copy(alpha = 0.58f)
                        drawRect(shade, size = Size(size.width, cropTop))
                        drawRect(shade, topLeft = Offset(0f, cropTop), size = Size(cropLeft, cropSize))
                        drawRect(shade, topLeft = Offset(cropLeft + cropSize, cropTop), size = Size(size.width - cropLeft - cropSize, cropSize))
                        drawRect(shade, topLeft = Offset(0f, cropTop + cropSize), size = Size(size.width, size.height - cropTop - cropSize))
                        drawRect(Color(0xFF3B82F6), topLeft = Offset(cropLeft, cropTop), size = Size(cropSize, cropSize), style = Stroke(2.dp.toPx()))
                    }
                    Text("Zoom ${((zoom / minimumZoom) * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = zoom,
                        onValueChange = { nextZoom ->
                            val clampedZoom = nextZoom.coerceIn(minimumZoom, 3f)
                            val nextScale = min(canvasSizePx / bitmap.width, canvasSizePx / bitmap.height) * clampedZoom
                            val maxX = max(0f, (bitmap.width * nextScale - cropSizePx) / 2f)
                            val maxY = max(0f, (bitmap.height * nextScale - cropSizePx) / 2f)
                            offset = Offset(
                                x = offset.x.coerceIn(-maxX, maxX),
                                y = offset.y.coerceIn(-maxY, maxY),
                            )
                            zoom = clampedZoom
                        },
                        valueRange = minimumZoom..3f,
                        enabled = !busy,
                    )
                } else {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text("Memuat foto…", Modifier.fillMaxWidth().padding(20.dp))
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(onClick = ::applyCrop, enabled = bitmap != null && !busy) {
                Text(if (busy) "Memproses…" else "Pangkas")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !busy) { Text("Batal") }
        },
    )
}