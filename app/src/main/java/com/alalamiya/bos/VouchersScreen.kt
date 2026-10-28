package com.alalamiya.bos

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.Account
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Voucher
import com.alalamiya.bos.data.VoucherLine
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class VDraft(val ref: AccRef, val name: String, val amount: String, val note: String)

@Composable
fun VouchersScreen(db: BosDatabase) {
    var editing by remember { mutableStateOf(false) }
    BackHandler(enabled = editing) { editing = false }
    if (editing) VoucherEditor(db) { editing = false } else VoucherList(db) { editing = true }
}

@Composable
private fun VoucherList(db: BosDatabase, onNew: () -> Unit) {
    val list by db.vouchers().observeRecent().collectAsStateWithLifecycle(emptyList())
    val parties by db.customers().observeAll().collectAsStateWithLifecycle(emptyList())
    val accounts by db.accounts().observeAllAccounts().collectAsStateWithLifecycle(emptyList())
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    var selected by remember { mutableStateOf<Voucher?>(null) }
    Scaffold(floatingActionButton = { FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "سند جديد") } }) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد سندات") }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { v ->
                    Card(onClick = { selected = v }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((if (v.type == "receipt") "سند قبض" else "سند صرف") + " #${v.id}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(fmt.format(Date(v.date)) + if (v.note.isNotBlank()) "  •  ${v.note}" else "", style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
                            }
                            Text(v.amount.money(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (v.type == "receipt") Color(0xFF2E7D32) else Color(0xFFB3261E))
                        }
                    }
                }
            }
        }
    }
    selected?.let { v -> VoucherDetail(db, v, parties.associate { it.id to it.name }, accounts.associate { it.id to it.name }) { selected = null } }
}

@Composable
private fun VoucherDetail(db: BosDatabase, v: Voucher, partyNames: Map<Long, String>, accNames: Map<Long, String>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    var lines by remember(v.id) { mutableStateOf<List<VoucherLine>>(emptyList()) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(v.id) { lines = withContext(Dispatchers.IO) { db.vouchers().linesOf(v.id) } }
    val rows = if (lines.isNotEmpty()) lines.map { l ->
        listOf(l.partyId?.let { partyNames[it] } ?: l.accountId?.let { accNames[it] } ?: "—", l.amount.money(), l.note)
    } else listOf(listOf(v.customerId?.let { partyNames[it] } ?: "—", v.amount.money(), ""))
    val title = (if (v.type == "receipt") "سند قبض" else "سند صرف") + " #${v.id}"
    val spec = PdfSpec(
        fileName = "voucher-${v.id}.pdf", title = title,
        subtitle = Prefs.header(ctx) + "\n" + fmt.format(Date(v.date)) + if (v.note.isNotBlank()) " — ${v.note}" else "",
        headers = listOf("الحساب", "المبلغ", "ملاحظة"), rows = rows, footer = listOf("الإجمالي: ${v.amount.money()}"), weights = listOf(2f, 1f, 1.5f)
    )
    AlertDialog(
        onDismissRequest = onClose, title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(fmt.format(Date(v.date)) + if (v.note.isNotBlank()) "  •  ${v.note}" else "", style = MaterialTheme.typography.bodySmall)
                rows.forEach { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(r[0], Modifier.weight(1f)); Text(r[1], fontWeight = FontWeight.Bold)
                    }
                    if (r[2].isNotBlank()) Text(r[2], style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Text("الإجمالي: ${v.amount.money()}", style = MaterialTheme.typography.titleSmall)
                PdfActions(spec)
            }
        },
        confirmButton = { TextButton(onClick = { confirm = true }) { Text("حذف السند", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onClose) { Text("إغلاق") } }
    )
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false }, title = { Text("حذف السند") },
            text = { Text("سيُحذف السند وقيوده، وتُعاد أرصدة الأطراف كما كانت قبله.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try { withContext(Dispatchers.IO) { Ledger(db).deleteVoucher(v.id) }; confirm = false; onClose() }
                        catch (e: Exception) { Toast.makeText(ctx, e.message ?: "تعذّر الحذف", Toast.LENGTH_SHORT).show(); confirm = false }
                    }
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun VoucherEditor(db: BosDatabase, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cashAccounts by db.accounts().observeCashAccounts().collectAsStateWithLifecycle(emptyList())
    val cashOptions = cashAccounts.ifEmpty { listOf(Account(code = "1100", name = "الصندوق", type = "asset")) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    var receipt by remember { mutableStateOf(true) }
    var fundCode by remember { mutableStateOf("1100") }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var remarks by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf<VDraft>() }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val fundName = cashOptions.firstOrNull { it.code == fundCode }?.name ?: cashOptions.first().name
    val parsed = lines.map { it.amount.toMilli() }
    val valid = lines.isNotEmpty() && parsed.all { it != null && it > 0 }
    val total = parsed.filterNotNull().sum()

    Column(Modifier.fillMaxSize().background(RefBg)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = receipt, onClick = { receipt = true }, label = { Text("سند قبض") })
                FilterChip(selected = !receipt, onClick = { receipt = false }, label = { Text("سند صرف") })
            }
            RefCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Spinner(fundName, cashOptions.map { it.name }, { fundCode = cashOptions[it].code })
                        Text(fmt.format(Date(date)), Modifier.padding(4.dp), fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { pickDate(ctx, date, false) { date = it } }) { Text("تغيير التاريخ") }
                    }
                    UnderlineField(remarks, { remarks = it }, "ملاحظات")
                }
            }
            Text(if (receipt) "الحسابات الدائنة (من أين استُلم المبلغ)" else "الحسابات المدينة (لمن صُرف المبلغ)", fontWeight = FontWeight.Bold)
            lines.forEachIndexed { i, l ->
                val a = l.amount.toMilli()
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(l.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { lines.removeAt(i) }) { Icon(Icons.Filled.Close, "حذف", tint = Color(0xFFB3261E)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                            NumField(l.amount, { lines[i] = l.copy(amount = it) }, Modifier.weight(1f), isError = a == null || a <= 0)
                            UnderlineField(l.note, { lines[i] = l.copy(note = it) }, "ملاحظة", Modifier.weight(1.4f))
                        }
                    }
                }
            }
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) { Text("+ إضافة حساب") }
        }
        Column(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("الإجمالي", style = MaterialTheme.typography.titleMedium)
                Text(total.money(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = RefBlue)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = valid && !busy, modifier = Modifier.weight(1f), onClick = {
                    scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) {
                                Ledger(db).postVoucherMulti(
                                    receipt, fundCode, date, remarks.trim(),
                                    lines.map { VLineIn(it.ref.partyId, it.ref.accountId, it.amount.toMilli() ?: 0L, it.note.trim()) }
                                )
                            }
                            onClose()
                        } catch (e: Exception) {
                            Toast.makeText(ctx, e.message ?: "تعذّر الحفظ", Toast.LENGTH_LONG).show()
                        }
                        busy = false
                    }
                }) { Text("حفظ السند") }
                TextButton(onClick = onClose) { Text("إلغاء") }
            }
        }
    }
    if (picking) {
        AccountPickerDialog(db, exclude = setOf("1200", "2100", fundCode), onDismiss = { picking = false }) { ref, name ->
            lines.add(VDraft(ref, name, "", ""))
            picking = false
        }
    }
}
