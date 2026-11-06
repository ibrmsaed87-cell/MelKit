package com.spinel.melkit.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.spinel.melkit.R
import com.spinel.melkit.storage.CoverPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun CoverEditor(existing: Bitmap?, save: suspend (Bitmap?) -> Boolean, dismiss: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var uri by rememberSaveable { mutableStateOf<String?>(null) }
    var zoom by rememberSaveable(uri) { mutableStateOf(1f) }
    var horizontal by rememberSaveable(uri) { mutableStateOf(0.5f) }
    var vertical by rememberSaveable(uri) { mutableStateOf(0.5f) }
    var photo by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var unreadable by remember(uri) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        chosen?.let { uri = it.toString() }
    }
    LaunchedEffect(uri) {
        val chosen = uri ?: return@LaunchedEffect
        photo = withContext(Dispatchers.IO) { runCatching { CoverPhoto.open(resolver, Uri.parse(chosen)) }.getOrNull() }
        unreadable = photo == null
    }
    val apply: (Bitmap?, android.graphics.Rect?) -> Unit = { image, crop ->
        busy = true
        failed = false
        scope.launch {
            val result = if (image != null && crop != null) {
                withContext(Dispatchers.Default) { runCatching { CoverPhoto.crop(image, crop) }.getOrNull() }
            } else null
            if ((image == null || result != null) && save(result)) dismiss() else failed = true
            busy = false
        }
    }
    val source = photo
    val area = source?.let { CoverPhoto.area(it, zoom, horizontal, vertical) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(R.string.cover_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { pick.launch(arrayOf("image/*")) }) {
                    Text(stringResource(R.string.cover_choose))
                }
                if (source != null && area != null) {
                    val preview = remember(source) { source.asImageBitmap() }
                    Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                        drawImage(preview, srcOffset = IntOffset(area.left, area.top), srcSize = IntSize(area.width(), area.height()),
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
                    }
                    Text(stringResource(R.string.cover_zoom))
                    Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..4f, enabled = !busy)
                    Text(stringResource(R.string.cover_horizontal))
                    Slider(value = horizontal, onValueChange = { horizontal = it }, enabled = !busy && area.width() < source.width)
                    Text(stringResource(R.string.cover_vertical))
                    Slider(value = vertical, onValueChange = { vertical = it }, enabled = !busy && area.height() < source.height)
                } else {
                    if (uri != null && !unreadable) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    if (existing != null) CoverPreview(existing, Modifier.fillMaxWidth().aspectRatio(1f))
                }
                if (unreadable) Text(stringResource(R.string.cover_read_failed), color = MaterialTheme.colorScheme.error)
                if (failed) Text(stringResource(R.string.cover_save_failed), color = MaterialTheme.colorScheme.error)
                if (existing != null) TextButton(enabled = !busy, onClick = { apply(null, null) }) {
                    Text(stringResource(R.string.cover_remove))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = source != null && area != null && !busy, onClick = { apply(source, area) }) {
                Text(stringResource(R.string.profile_save))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text(stringResource(R.string.saved_cancel)) } },
    )
}

@Composable
internal fun CoverPreview(photo: Bitmap, modifier: Modifier) {
    val preview = remember(photo) { photo.asImageBitmap() }
    Image(preview, contentDescription = stringResource(R.string.cover_preview), modifier = modifier)
}
