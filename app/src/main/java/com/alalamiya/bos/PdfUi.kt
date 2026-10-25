package com.alalamiya.bos

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_PREVIEW_PAGES = 30

private fun renderPages(file: File): List<Bitmap> {
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(pfd)
    try {
        val out = mutableListOf<Bitmap>()
        for (i in 0 until minOf(renderer.pageCount, MAX_PREVIEW_PAGES)) {
            val page = renderer.openPage(i)
            try {
                val bmp = Bitmap.createBitmap((page.width * 1.6f).toInt(), (page.height * 1.6f).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                out.add(bmp)
            } finally {
                page.close()
            }
        }
        return out
    } finally {
        renderer.close()
        pfd.close()
    }
}

/** زر «تصدير PDF»: يُنشئ الملف ويفتحه للمعاينة أولًا، ثم يختار المستخدم تصديره (حفظه) أو لا. */
@Composable
fun PdfActions(spec: PdfSpec) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<File?>(null) }
    OutlinedButton(onClick = {
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                try {
                    File(ctx.cacheDir, "preview.pdf").also { out -> out.outputStream().use { PdfExport.write(ctx, it, spec) } }
                } catch (e: Exception) { null }
            }
            if (f == null) Toast.makeText(ctx, "تعذّر إنشاء ملف PDF", Toast.LENGTH_SHORT).show() else file = f
        }
    }) { Text("تصدير PDF") }
    file?.let { PdfPreview(it, spec) { file = null } }
}

@Composable
fun PdfPreview(file: File, spec: PdfSpec, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pages by remember(file) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var total by remember(file) { mutableStateOf(0) }
    LaunchedEffect(file) {
        pages = withContext(Dispatchers.IO) { renderPages(file) }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val os = ctx.contentResolver.openOutputStream(uri, "w") ?: error("x")
                    os.use { o -> file.inputStream().use { it.copyTo(o) } }
                    true
                } catch (e: Exception) { false }
            }
            Toast.makeText(ctx, if (ok) "تم تصدير الملف" else "تعذّر التصدير", Toast.LENGTH_SHORT).show()
            if (ok) onClose()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF424242)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(BosBlueDark).padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(spec.title, Modifier.weight(1f), color = Color.White, maxLines = 1)
                    Button(onClick = { saver.launch(spec.fileName) }) { Text("تصدير") }
                    TextButton(onClick = { PdfExport.printFile(ctx, file, spec.title) }) { Text("طباعة", color = Color.White) }
                    TextButton(onClick = onClose) { Text("إغلاق", color = Color.White) }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pages) { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.fillMaxWidth().background(Color.White), contentScale = ContentScale.FillWidth
                        )
                    }
                }
            }
        }
    }
}

/** يفتح معاينة PDF عندما تصبح show = true (للأزرار الموجودة في أشرطة العنوان). */
@Composable
fun PdfPreviewHost(spec: PdfSpec, show: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var file by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(show) {
        if (show) {
            val f = withContext(Dispatchers.IO) {
                try {
                    File(ctx.cacheDir, "preview.pdf").also { out -> out.outputStream().use { PdfExport.write(ctx, it, spec) } }
                } catch (e: Exception) { null }
            }
            if (f == null) {
                Toast.makeText(ctx, "تعذّر إنشاء ملف PDF", Toast.LENGTH_SHORT).show()
                onClose()
            } else {
                file = f
            }
        } else {
            file = null
        }
    }
    file?.let { PdfPreview(it, spec) { file = null; onClose() } }
}
