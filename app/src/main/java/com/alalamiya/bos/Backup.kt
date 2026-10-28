package com.alalamiya.bos

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.alalamiya.bos.data.BosDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** النسخ الاحتياطي والاستعادة: الاستيراد الوحيد المسموح هو ملف قاعدة البيانات الذي أنشأه هذا التطبيق نفسه. */
object Backup {
    private val VERSIONS = 1..5   // الإصدار 1 يُرقّى تلقائيًا عند الفتح
    private val REQUIRED = listOf(
        "customers", "items", "accounts", "journal_entries", "journal_lines",
        "invoices", "invoice_lines", "vouchers", "operation_log"
    )

    data class Summary(val customers: Long, val items: Long, val invoices: Long, val vouchers: Long)

    private fun checkpoint(bos: BosDatabase) {
        bos.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
    }

    fun snapshot(ctx: Context, bos: BosDatabase): File {
        checkpoint(bos)
        val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
        val f = File(dir, "snap-${System.currentTimeMillis()}.db")
        ctx.getDatabasePath("bos.db").copyTo(f, overwrite = true)
        return f
    }

    fun latestSnapshot(ctx: Context): File? =
        File(ctx.filesDir, "backups").listFiles()
            ?.filter { it.name.startsWith("snap-") }
            ?.maxByOrNull { it.lastModified() }

    /** نسخة تلقائية يومية داخل التطبيق، يُحتفظ بآخر 7. (للحماية الكاملة صدّر نسخة خارج الجهاز بشكل دوري.) */
    fun autoBackup(ctx: Context) {
        checkpoint(BosDatabase.get(ctx))
        val dir = File(ctx.filesDir, "auto").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        ctx.getDatabasePath("bos.db").copyTo(File(dir, "auto-$stamp.db"), overwrite = true)
        dir.listFiles()?.sortedByDescending { it.name }?.drop(7)?.forEach { it.delete() }
    }

    fun latestAuto(ctx: Context): File? =
        File(ctx.filesDir, "auto").listFiles()?.maxByOrNull { it.name }

    fun writeTo(ctx: Context, bos: BosDatabase, uri: Uri) {
        checkpoint(bos)
        val out = ctx.contentResolver.openOutputStream(uri, "w") ?: error("تعذّر الكتابة في المكان المختار")
        out.use { o -> ctx.getDatabasePath("bos.db").inputStream().use { i -> i.copyTo(o) } }
    }

    fun validate(ctx: Context, uri: Uri): Pair<File, Summary> {
        val f = File(ctx.cacheDir, "restore_src.db")
        val input = ctx.contentResolver.openInputStream(uri) ?: error("تعذّر قراءة الملف")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return validateFile(f)
    }

    /** يفحص الملف بصرامة قبل السماح باستعادته. */
    fun validateFile(f: File): Pair<File, Summary> {
        val db = try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            throw IllegalStateException("الملف ليس قاعدة بيانات صالحة")
        }
        try {
            val ok = db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
            if (!ok) throw IllegalStateException("الملف تالف (فشل فحص السلامة)")
            val version = db.rawQuery("PRAGMA user_version", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (version !in VERSIONS) throw IllegalStateException("الملف من إصدار غير متوافق مع هذا التطبيق")
            val names = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> while (c.moveToNext()) names.add(c.getString(0)) }
            if (REQUIRED.any { it !in names }) throw IllegalStateException("الملف ليس نسخة من هذا التطبيق (جداول ناقصة)")
            fun count(t: String) = db.rawQuery("SELECT COUNT(*) FROM $t", null).use { if (it.moveToFirst()) it.getLong(0) else 0L }
            return f to Summary(count("customers"), count("items"), count("invoices"), count("vouchers"))
        } finally {
            db.close()
        }
    }

    fun scheduleRestore(ctx: Context, src: File) {
        src.copyTo(File(ctx.filesDir, "pending_restore.db"), overwrite = true)
    }
}
