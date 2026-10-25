package com.alalamiya.bos

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.widget.Toast
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * طباعة مباشرة على طابعة حرارية ESC/POS عبر بلوتوث. تُرسم الفاتورة كصورة ثم تُرسل كنقاط،
 * فيعمل العربي على أي طابعة دون الاعتماد على صفحات الترميز فيها.
 */
object Thermal {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    fun hasPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun pairedDevices(ctx: Context): List<Pair<String, String>> {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return emptyList()
        return adapter.bondedDevices.map { (it.name ?: it.address) to it.address }
    }

    fun renderReceipt(spec: PdfSpec, widthPx: Int): Bitmap {
        val pad = 8
        val textW = widthPx - 2 * pad
        val size = if (widthPx >= 500) 26f else 22f
        val normal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; color = Color.BLACK }
        val bold = TextPaint(normal).apply { typeface = Typeface.DEFAULT_BOLD }
        val big = TextPaint(bold).apply { textSize = size + 6f }
        fun lay(text: String, p: TextPaint, a: Layout.Alignment): StaticLayout =
            StaticLayout.Builder.obtain(text, 0, text.length, p, textW)
                .setAlignment(a).setTextDirection(TextDirectionHeuristics.ANYRTL_LTR).build()

        val blocks = mutableListOf<Pair<StaticLayout?, Int>>()
        blocks.add(lay(spec.title, big, Layout.Alignment.ALIGN_CENTER) to 6)
        spec.subtitle.split("\n").filter { it.isNotBlank() }.forEach { blocks.add(lay(it, normal, Layout.Alignment.ALIGN_CENTER) to 2) }
        blocks.add(null to 8)
        for (r in spec.rows) {
            blocks.add(lay(r.getOrElse(0) { "" }, bold, Layout.Alignment.ALIGN_NORMAL) to 0)
            val q = r.getOrElse(1) { "" }
            val pr = r.getOrElse(2) { "" }
            val tt = r.getOrElse(3) { "" }
            blocks.add(lay(if (q.isBlank() && pr.isBlank()) tt else "$q × $pr = $tt", normal, Layout.Alignment.ALIGN_NORMAL) to 6)
        }
        blocks.add(null to 8)
        spec.footer.forEach { blocks.add(lay(it, bold, Layout.Alignment.ALIGN_NORMAL) to 4) }
        blocks.add(lay("شكرًا لتعاملكم معنا", normal, Layout.Alignment.ALIGN_CENTER) to 24)

        var total = 2 * pad
        for ((l, gap) in blocks) total += (l?.height ?: 2) + gap
        val bmp = Bitmap.createBitmap(widthPx, total, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val rule = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
        var y = pad
        for ((l, gap) in blocks) {
            if (l == null) {
                canvas.drawLine(pad.toFloat(), (y + 1).toFloat(), (widthPx - pad).toFloat(), (y + 1).toFloat(), rule)
                y += 2 + gap
            } else {
                canvas.save()
                canvas.translate(pad.toFloat(), y.toFloat())
                l.draw(canvas)
                canvas.restore()
                y += l.height + gap
            }
        }
        return bmp
    }

    private fun escPos(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40))
        val wBytes = (bmp.width + 7) / 8
        val pixels = IntArray(bmp.width)
        var y = 0
        while (y < bmp.height) {
            val h = minOf(128, bmp.height - y)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, (wBytes and 0xFF).toByte(), (wBytes shr 8).toByte(), (h and 0xFF).toByte(), (h shr 8).toByte()))
            for (row in y until y + h) {
                bmp.getPixels(pixels, 0, bmp.width, 0, row, bmp.width, 1)
                for (bx in 0 until wBytes) {
                    var b = 0
                    for (bit in 0 until 8) {
                        val x = bx * 8 + bit
                        if (x < bmp.width) {
                            val c = pixels[x]
                            val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                            if (lum < 128) b = b or (0x80 shr bit)
                        }
                    }
                    out.write(b)
                }
            }
            y += h
        }
        out.write(byteArrayOf(0x1B, 0x64, 0x04))
        return out.toByteArray()
    }

    /** يرجع رسالة نتيجة جاهزة للعرض. */
    @SuppressLint("MissingPermission")
    fun print(ctx: Context, spec: PdfSpec): String {
        val addr = Prefs.printerAddress(ctx)
        if (addr.isBlank()) return "اختر الطابعة أولًا من الإعدادات"
        if (!hasPermission(ctx)) return "امنح التطبيق إذن البلوتوث من الإعدادات"
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) return "فعّل البلوتوث أولًا"
        val width = if (Prefs.paperMm(ctx) >= 80) 576 else 384
        return try {
            val data = escPos(renderReceipt(spec, width))
            val socket = adapter.getRemoteDevice(addr).createRfcommSocketToServiceRecord(SPP)
            try {
                socket.connect()
                val os = socket.outputStream
                var i = 0
                while (i < data.size) {
                    val n = minOf(1024, data.size - i)
                    os.write(data, i, n)
                    i += n
                    Thread.sleep(25)
                }
                os.flush()
                Thread.sleep(300)
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
            "تمت الطباعة"
        } catch (e: Exception) {
            "تعذّرت الطباعة: ${e.message}"
        }
    }
}

@Composable
fun ThermalButton(spec: PdfSpec) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = {
        scope.launch {
            val msg = withContext(Dispatchers.IO) { Thermal.print(ctx, spec) }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }) { Text("طباعة حرارية") }
}
