package com.alalamiya.bos

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

data class PdfSpec(
    val fileName: String,
    val title: String,
    val subtitle: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    val footer: List<String>,
    val weights: List<Float> = emptyList()
)

object PdfExport {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val M = 36f
    private const val ROW_H = 20f

    fun write(ctx: Context, out: OutputStream, spec: PdfSpec) {
        val doc = PdfDocument()
        val w = if (spec.weights.size == spec.headers.size) spec.weights else List(spec.headers.size) { 1f }
        val usable = PAGE_W - 2 * M
        val colW = w.map { it / w.sum() * usable }

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.BLACK; textAlign = Paint.Align.RIGHT }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val white = Paint(bold).apply { color = Color.WHITE }
        val titleP = Paint(bold).apply { textSize = 16f; textAlign = Paint.Align.CENTER }
        val subP = Paint(text).apply { textAlign = Paint.Align.CENTER }
        val fill = Paint().apply { color = 0xFF1976D2.toInt() }
        val rule = Paint().apply { color = 0xFFCCCCCC.toInt(); strokeWidth = 0.5f }

        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        var canvas = page.canvas
        var y = M

        fun cell(p: Paint, s: String, i: Int, baseline: Float) {
            val right = PAGE_W - M - colW.take(i).sum()
            val shown = TextUtils.ellipsize(s, TextPaint(p), colW[i] - 8f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, right - 4f, baseline, p)
        }

        fun headerRow() {
            canvas.drawRect(M, y, PAGE_W - M, y + ROW_H, fill)
            spec.headers.forEachIndexed { i, h -> cell(white, h, i, y + 14f) }
            y += ROW_H
        }

        canvas.drawText(spec.title, PAGE_W / 2f, y + 16f, titleP); y += 24f
        spec.subtitle.split("\n").filter { it.isNotBlank() }.forEach { canvas.drawText(it, PAGE_W / 2f, y + 10f, subP); y += 15f }
        y += 8f
        headerRow()

        for (r in spec.rows) {
            if (y + ROW_H > PAGE_H - M) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                canvas = page.canvas
                y = M
                headerRow()
            }
            r.forEachIndexed { i, s -> if (i < spec.headers.size) cell(text, s, i, y + 14f) }
            y += ROW_H
            canvas.drawLine(M, y, PAGE_W - M, y, rule)
        }

        y += 10f
        for (f in spec.footer) {
            if (y + ROW_H > PAGE_H - M) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
                canvas = page.canvas
                y = M
            }
            canvas.drawText(f, PAGE_W - M, y + 14f, bold)
            y += ROW_H
        }

        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }

    fun printFile(ctx: Context, file: File, jobName: String) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback, extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
                callback.onLayoutFinished(
                    PrintDocumentInfo.Builder(jobName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(),
                    true
                )
            }

            override fun onWrite(
                pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                try {
                    file.inputStream().use { i -> FileOutputStream(destination.fileDescriptor).use { o -> i.copyTo(o) } }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                }
            }
        }, null)
    }
}

