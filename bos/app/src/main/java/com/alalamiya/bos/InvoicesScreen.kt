package com.alalamiya.bos

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.alalamiya.bos.data.Invoice
import com.alalamiya.bos.data.InvoiceLine
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Customer
import com.alalamiya.bos.data.Item
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TYPES = listOf("sale" to "بيع", "purchase" to "شراء", "sale_return" to "مرتجع بيع", "purchase_return" to "مرتجع شراء")
private fun groupTypes(g: String) = if (g == "sales") listOf("sale", "sale_return") else listOf("purchase", "purchase_return")
internal fun typeLabel(t: String) = TYPES.firstOrNull { it.first == t }?.second ?: t

data class LineDraft(val item: Item, val qty: String, val price: String)

@Composable
fun InvoicesScreen(db: BosDatabase, group: String) {
    var editing by remember { mutableStateOf(false) }
    if (editing) InvoiceEditor(db, group) { editing = false } else InvoiceList(db, group) { editing = true }
}

@Composable
private fun InvoiceList(db: BosDatabase, group: String, onNew: () -> Unit) {
    val all by db.invoices().observeRecent().collectAsStateWithLifecycle(emptyList())
    val list = remember(all, group) { all.filter { it.type in groupTypes(group) } }
    val customers by db.customers().observeAll().collectAsStateWithLifecycle(emptyList())
    val allItems by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    val names = remember(customers) { customers.associate { it.id to it.name } }
    val itemNames = remember(allItems) { allItems.associate { it.id to it.name } }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    var selected by remember { mutableStateOf<Invoice?>(null) }
    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "فاتورة جديدة") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { inv ->
                Card(onClick = { selected = inv }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("${typeLabel(inv.type)} #${inv.id} — ${names[inv.customerId] ?: "نقدي"}", style = MaterialTheme.typography.titleSmall)
                            Text(fmt.format(Date(inv.date)), style = MaterialTheme.typography.bodySmall)
                            if (inv.cancelled) {
                                Text("ملغاة", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            } else if (inv.total != inv.paid) {
                                Text("المتبقي: ${(inv.total - inv.paid).money()}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(inv.total.money(), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
    selected?.let { inv ->
        InvoiceDetail(db, inv, names[inv.customerId], itemNames, fmt) { selected = null }
    }
}

@Composable
private fun InvoiceDetail(
    db: BosDatabase, inv: Invoice, party: String?, itemNames: Map<Long, String>,
    fmt: SimpleDateFormat, onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember(inv.id) { mutableStateOf<List<InvoiceLine>>(emptyList()) }
    var confirm by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(inv.id) { lines = withContext(Dispatchers.IO) { db.invoices().lines(inv.id) } }

    val rows = lines.map { l ->
        listOf(itemNames[l.itemId] ?: "#${l.itemId}", l.quantity.money(), l.price.money(), Ledger.lineTotal(l.quantity, l.price).money())
    }
    val title = "${typeLabel(inv.type)} #${inv.id}" + if (inv.cancelled) " (ملغاة)" else ""
    val spec = PdfSpec(
        fileName = "invoice-${inv.id}.pdf",
        title = title,
        subtitle = Prefs.header(ctx) + "\n" + (party ?: "نقدي") + " — " + fmt.format(Date(inv.date)),
        headers = listOf("الصنف", "الكمية", "السعر", "الإجمالي"),
        rows = rows,
        footer = listOf("الإجمالي: ${inv.total.money()}", "المدفوع: ${inv.paid.money()}", "المتبقي: ${(inv.total - inv.paid).money()}"),
        weights = listOf(2.4f, 1f, 1f, 1.2f)
    )

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text((party ?: "نقدي") + " — " + fmt.format(Date(inv.date)), style = MaterialTheme.typography.bodySmall)
                rows.forEach { r ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(r[0], Modifier.weight(2f))
                        Text("${r[1]} × ${r[2]}", Modifier.weight(1.5f), style = MaterialTheme.typography.bodySmall)
                        Text(r[3], Modifier.weight(1f))
                    }
                }
                Text("الإجمالي: ${inv.total.money()}", style = MaterialTheme.typography.titleSmall)
                Text("المدفوع: ${inv.paid.money()}   المتبقي: ${(inv.total - inv.paid).money()}", style = MaterialTheme.typography.bodySmall)
                PdfActions(spec)
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (!inv.cancelled) {
                TextButton(onClick = { confirm = true }) { Text("إلغاء الفاتورة", color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("إغلاق") } }
    )

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("تأكيد إلغاء الفاتورة") },
            text = { Text("سيُعكس أثر الفاتورة على المخزون وأرصدة الأطراف ويُنشأ قيد عكسي. لا يمكن التراجع عن الإلغاء.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Ledger(db).cancelInvoice(inv.id) }
                            confirm = false
                            onClose()
                        } catch (e: Exception) {
                            err = e.message
                            confirm = false
                        }
                    }
                }) { Text("إلغاء الفاتورة") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("رجوع") } }
        )
    }
}

@Composable
private fun InvoiceEditor(db: BosDatabase, group: String, onClose: () -> Unit) {
    val items by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    val customers by db.customers().observeAll().collectAsStateWithLifecycle(emptyList())
    val ledger = remember { Ledger(db) }
    val scope = rememberCoroutineScope()

    var type by remember { mutableStateOf(groupTypes(group).first()) }
    var partyQuery by remember { mutableStateOf("") }
    var party by remember { mutableStateOf<Customer?>(null) }
    val lines = remember { mutableStateListOf<LineDraft>() }
    var itemQuery by remember { mutableStateOf("") }
    var paidText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        val code = r.contents
        if (code != null) scope.launch {
            val found = withContext(Dispatchers.IO) { db.items().findByBarcode(code) }
            if (found == null) {
                error = "لا يوجد صنف بهذا الباركود"
            } else {
                val price = if (type == "sale" || type == "sale_return") found.salePrice else found.costPrice
                lines.add(LineDraft(found, "1", price.money()))
                error = null
            }
        }
    }

    val parsed = lines.map { l ->
        val q = l.qty.toMilli()
        val p = l.price.toMilli()
        if (q == null || p == null || q <= 0 || p < 0) null else InvoiceLineInput(l.item.id, q, p)
    }
    val allValid = lines.isNotEmpty() && parsed.all { it != null }
    val total = parsed.filterNotNull().sumOf { Ledger.lineTotal(it.quantity, it.price) }
    val paidV: Long? = if (paidText.isBlank()) total else paidText.toMilli()
    val paidOk = paidV != null && paidV in 0L..total && (party != null || paidV == total)

    val partyMatches = remember(partyQuery, customers) {
        if (partyQuery.isBlank()) emptyList() else customers.filter { it.name.contains(partyQuery.trim()) }.take(5)
    }
    val itemMatches = remember(itemQuery, items) {
        if (itemQuery.isBlank()) emptyList() else items.filter { it.name.contains(itemQuery.trim()) }.take(5)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("فاتورة جديدة", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("إلغاء") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TYPES.filter { it.first in groupTypes(group) }.forEach { (k, v) ->
                FilterChip(selected = type == k, onClick = {
                    type = k
                    lines.clear(); error = null
                }, label = { Text(v) })
            }
        }

        if (party == null) {
            OutlinedTextField(partyQuery, { partyQuery = it }, label = { Text("العميل / المورد (اختياري للنقدي)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            partyMatches.forEach { c -> TextButton(onClick = { party = c }) { Text("${c.name}  (${c.balance.money()})") } }
        } else {
            TextButton(onClick = { party = null }) { Text("الطرف: ${party!!.name} — تغيير") }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(itemQuery, { itemQuery = it }, label = { Text("ابحث عن صنف لإضافته") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { scanner.launch(ScanOptions().setPrompt("امسح باركود الصنف")) }) { Text("مسح") }
        }
        itemMatches.forEach { it0 ->
            TextButton(onClick = {
                val price = if (type == "sale" || type == "sale_return") it0.salePrice else it0.costPrice
                lines.add(LineDraft(it0, "1", price.money()))
                itemQuery = ""
            }) { Text("${it0.name}  (المخزون ${it0.quantity.money()})") }
        }

        lines.forEachIndexed { idx, l ->
            val lq = l.qty.toMilli()
            val lp = l.price.toMilli()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(l.item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { lines.removeAt(idx) }) { Icon(Icons.Filled.Delete, "حذف") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(l.qty, { lines[idx] = l.copy(qty = it) }, label = { Text("الكمية") }, singleLine = true, isError = lq == null || lq <= 0, modifier = Modifier.weight(1f))
                        OutlinedTextField(l.price, { lines[idx] = l.copy(price = it) }, label = { Text("السعر") }, singleLine = true, isError = lp == null || lp < 0, modifier = Modifier.weight(1f))
                    }
                    if (lq != null && lp != null && lq > 0 && lp >= 0) Text("الإجمالي: ${Ledger.lineTotal(lq, lp).money()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text("إجمالي الفاتورة: ${total.money()}", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(paidText, { paidText = it }, label = { Text("المدفوع (اتركه فارغًا = كامل المبلغ)") }, singleLine = true, isError = !paidOk, modifier = Modifier.fillMaxWidth())
        if (paidV != null && paidV in 0L..total && paidV < total && party == null) {
            Text("الفاتورة الآجلة تحتاج اختيار عميل", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(note, { note = it }, label = { Text("ملاحظة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(modifier = Modifier.fillMaxWidth(), enabled = allValid && paidOk && !busy, onClick = {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        ledger.postInvoice(type, party?.id, parsed.filterNotNull(), paidV ?: total, note.trim())
                    }
                    onClose()
                } catch (e: Exception) {
                    error = "لم تُحفظ الفاتورة: ${e.message}"
                }
                busy = false
            }
        }) { Text("حفظ الفاتورة") }
    }
}
