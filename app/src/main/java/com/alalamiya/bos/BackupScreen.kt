package com.alalamiya.bos

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alalamiya.bos.data.BosDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

@Composable
fun BackupScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<File, Backup.Summary>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmUndo by remember { mutableStateOf(false) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = try {
                withContext(Dispatchers.IO) { Backup.writeTo(ctx, db, uri) }
                "تم حفظ النسخة بنجاح"
            } catch (e: Exception) {
                "تعذّر الحفظ: ${e.message}"
            }
            busy = false
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                pending = withContext(Dispatchers.IO) { Backup.validate(ctx, uri) }
                message = null
            } catch (e: Exception) {
                message = e.message
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("النسخ الاحتياطي والاستيراد", style = MaterialTheme.typography.titleMedium)
        Text("الاستيراد يقبل فقط ملف قاعدة البيانات الذي أنشأه هذا التطبيق نفسه عبر «تصدير نسخة»، ويُفحص الملف بالكامل قبل أي استعادة.", style = MaterialTheme.typography.bodySmall)
        Button(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            exporter.launch("alalamiya-backup-$stamp.db")
        }) { Text("تصدير نسخة من البيانات") }
        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { importer.launch(arrayOf("*/*")) }) {
            Text("استيراد (استعادة) من ملف")
        }
        if (Backup.latestSnapshot(ctx) != null) {
            TextButton(onClick = { confirmUndo = true }) { Text("التراجع عن آخر استعادة") }
        }
        if (Backup.latestAuto(ctx) != null) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        pending = withContext(Dispatchers.IO) { Backup.validateFile(Backup.latestAuto(ctx)!!) }
                        message = null
                    } catch (e: Exception) {
                        message = e.message
                    }
                    busy = false
                }
            }) { Text("استعادة آخر نسخة تلقائية") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    pending?.let { (file, s) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("استعادة نسخة") },
            text = {
                Text("الملف سليم ويحتوي: ${s.customers} عميل، ${s.items} صنف، ${s.invoices} فاتورة، ${s.vouchers} سند.\n\nستحلّ هذه البيانات محل كل بيانات التطبيق الحالية (تُحفظ نسخة من الحالية للتراجع). سيُغلق التطبيق ثم افتحه من جديد.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            Backup.snapshot(ctx, db)
                            Backup.scheduleRestore(ctx, file)
                        }
                        exitProcess(0)
                    }
                }) { Text("استعادة") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("إلغاء") } }
        )
    }

    if (confirmUndo) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("التراجع عن آخر استعادة") },
            text = { Text("ستعود البيانات كما كانت قبل آخر استعادة، وأي بيانات أُدخلت بعدها ستُفقد. سيُغلق التطبيق ثم افتحه من جديد.") },
            confirmButton = {
                TextButton(onClick = {
                    Backup.latestSnapshot(ctx)?.let { Backup.scheduleRestore(ctx, it) }
                    exitProcess(0)
                }) { Text("تراجع") }
            },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("إلغاء") } }
        )
    }
}
