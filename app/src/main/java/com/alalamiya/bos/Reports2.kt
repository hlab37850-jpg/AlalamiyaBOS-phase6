package com.alalamiya.bos

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Item
import com.alalamiya.bos.data.ItemMove
import com.alalamiya.bos.data.OperationLog
import com.alalamiya.bos.ui.money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val WHITE_CARD @Composable get() = CardDefaults.cardColors(containerColor = Color.White)

data class StmtRow(val date: Long, val label: String, val delta: Long, val running: Long)

@Composable
fun StatementScreen(db: BosDatabase, customerId: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val customer by db.customers().observeById(customerId).collectAsStateWithLifecycle(null)
    var opening by remember { mutableLongStateOf(0L) }
    var rows by remember { mutableStateOf<List<StmtRow>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val c = customer
    val bal = c?.balance

    LaunchedEffect(customerId, bal) {
        if (bal != null) {
            val data = withContext(Dispatchers.IO) {
                val inv = db.invoices().forCustomer(customerId).filter { !it.cancelled }.map { i ->
                    val rest = i.total - i.paid
                    val d = if (i.type == "sale" || i.type == "purchase_return") rest else -rest
                    Triple(i.date, "${typeLabel(i.type)} #${i.id}", d)
                }
                val vs = db.vouchers().forCustomer(customerId).map { v ->
                    Triple(v.date, if (v.type == "receipt") "سند قبض" else "سند صرف", if (v.type == "receipt") -v.amount else v.amount)
                }
                (inv + vs).filter { it.third != 0L }.sortedBy { it.first }
            }
            val open = bal - data.sumOf { it.third }
            var run = open
            val out = mutableListOf<StmtRow>()
            for (d in data) {
                run += d.third
                out.add(StmtRow(d.first, d.second, d.third, run))
            }
            opening = open
            rows = out
        }
    }

    if (c == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("العميل غير موجود") }
    } else {
        val spec = PdfSpec(
            fileName = "statement-${c.id}.pdf",
            title = "كشف حساب: ${c.name}",
            subtitle = Prefs.header(ctx) + (if (c.phone.isNotBlank()) "\nالهاتف: ${c.phone}" else ""),
            headers = listOf("التاريخ", "البيان", "مدين", "دائن", "الرصيد"),
            rows = listOf(listOf("", "رصيد سابق", "", "", opening.money())) + rows.map { r ->
                listOf(fmt.format(Date(r.date)), r.label, if (r.delta > 0) r.delta.money() else "", if (r.delta < 0) (-r.delta).money() else "", r.running.money())
            },
            footer = listOf("الرصيد الحالي: ${c.balance.money()}"),
            weights = listOf(1.3f, 1.7f, 1f, 1f, 1.2f)
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = WHITE_CARD) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (c.phone.isNotBlank()) Text("الهاتف: ${c.phone}")
                        if (c.address.isNotBlank()) Text("العنوان: ${c.address}")
                        Text(
                            "الرصيد الحالي: ${c.balance.money()}" + if (c.balance > 0) "  (مدين)" else if (c.balance < 0) "  (دائن)" else "",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text("موعد السداد: " + (c.dueDate?.let { fmt.format(Date(it)) } ?: "غير محدد"))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editing = true }) { Text("تعديل") }
                            OutlinedButton(onClick = {
                                val cal = Calendar.getInstance()
                                c.dueDate?.let { cal.timeInMillis = it }
                                DatePickerDialog(ctx, { _, y, m, d ->
                                    val t = Calendar.getInstance().apply { set(y, m, d, 23, 59, 0) }
                                    scope.launch(Dispatchers.IO) { db.customers().update(c.copy(dueDate = t.timeInMillis)) }
                                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                            }) { Text("تحديد موعد سداد") }
                            if (c.dueDate != null) {
                                TextButton(onClick = { scope.launch(Dispatchers.IO) { db.customers().update(c.copy(dueDate = null)) } }) { Text("مسح الموعد") }
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().background(BosBlue).padding(8.dp)) {
                    listOf("التاريخ" to 1.3f, "البيان" to 1.7f, "مدين" to 1f, "دائن" to 1f, "الرصيد" to 1.2f).forEach { (t, w) ->
                        Text(t, Modifier.weight(w), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().background(Color(0xFFE3F0FF)).padding(8.dp)) {
                    Text("", Modifier.weight(1.3f)); Text("رصيد سابق", Modifier.weight(1.7f), style = MaterialTheme.typography.bodySmall)
                    Text("", Modifier.weight(1f)); Text("", Modifier.weight(1f))
                    Text(opening.money(), Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                }
            }
            items(rows) { r ->
                Row(Modifier.fillMaxWidth().background(Color.White).padding(8.dp)) {
                    Text(fmt.format(Date(r.date)), Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
                    Text(r.label, Modifier.weight(1.7f), style = MaterialTheme.typography.bodySmall)
                    Text(if (r.delta > 0) r.delta.money() else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(if (r.delta < 0) (-r.delta).money() else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(r.running.money(), Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("الرصيد الحالي: ${c.balance.money()}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    PdfActions(spec)
                }
            }
        }
    }

    if (editing && c != null) {
        CustomerDialog(c, { editing = false }) { u ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    db.customers().update(u)
                    db.log().insert(OperationLog(action = "edit_customer", details = u.name))
                }
                editing = false
            }
        }
    }
}

private fun periodStart(p: Int): Long {
    if (p == 3) return 0L
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    if (p == 1) c.set(Calendar.DAY_OF_MONTH, 1)
    if (p == 2) { c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.MONTH, Calendar.JANUARY) }
    return c.timeInMillis
}

@Composable
fun ProfitLossScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    var period by remember { mutableIntStateOf(3) }
    val from = remember(period) { periodStart(period) }
    val rows by remember(from) { db.journal().observePnl(from) }.collectAsStateWithLifecycle(emptyList())
    val income = rows.filter { it.type == "income" }.map { it.name to (it.credit - it.debit) }
    val expense = rows.filter { it.type == "expense" }.map { it.name to (it.debit - it.credit) }
    val totalIncome = income.sumOf { it.second }
    val totalExpense = expense.sumOf { it.second }
    val net = totalIncome - totalExpense
    val labels = listOf("اليوم", "هذا الشهر", "هذه السنة", "الكل")

    val pdfRows = mutableListOf<List<String>>()
    pdfRows.add(listOf("الإيرادات", ""))
    income.forEach { pdfRows.add(listOf("   ${it.first}", it.second.money())) }
    pdfRows.add(listOf("إجمالي الإيرادات", totalIncome.money()))
    pdfRows.add(listOf("المصروفات والتكاليف", ""))
    expense.forEach { pdfRows.add(listOf("   ${it.first}", it.second.money())) }
    pdfRows.add(listOf("إجمالي المصروفات", totalExpense.money()))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, l -> FilterChip(selected = period == i, onClick = { period = i }, label = { Text(l) }) }
        }
        Card(Modifier.fillMaxWidth(), colors = WHITE_CARD) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("الإيرادات", fontWeight = FontWeight.Bold)
                income.forEach { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(it.first); Text(it.second.money()) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("الإجمالي", fontWeight = FontWeight.Bold); Text(totalIncome.money(), fontWeight = FontWeight.Bold) }
                Text("المصروفات والتكاليف", fontWeight = FontWeight.Bold)
                expense.forEach { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(it.first); Text(it.second.money()) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("الإجمالي", fontWeight = FontWeight.Bold); Text(totalExpense.money(), fontWeight = FontWeight.Bold) }
            }
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFD6E6FA))) {
            Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (net >= 0) "صافي الربح" else "صافي الخسارة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(net.money(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
        PdfActions(
            PdfSpec(
                fileName = "profit-loss.pdf",
                title = "الأرباح والخسائر — ${labels[period]}",
                subtitle = Prefs.header(ctx),
                headers = listOf("البند", "المبلغ"),
                rows = pdfRows,
                footer = listOf((if (net >= 0) "صافي الربح: " else "صافي الخسارة: ") + net.money()),
                weights = listOf(2f, 1f)
            )
        )
    }
}

private data class MvEntry(val date: Long, val label: String, val qty: Long)

@Composable
fun MovementScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val items by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    var item by remember { mutableStateOf<Item?>(null) }
    var entries by remember { mutableStateOf<List<MvEntry>>(emptyList()) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val matches = remember(query, items) {
        if (query.isBlank()) emptyList() else items.filter { it.name.contains(query.trim()) || it.barcode == query.trim() }.take(6)
    }
    val cur = item?.let { s -> items.firstOrNull { it.id == s.id } ?: s }
    LaunchedEffect(cur?.id, cur?.quantity) {
        val c = cur
        entries = if (c == null) emptyList() else withContext(Dispatchers.IO) {
            val inv = db.invoices().movesFor(c.id).map { m ->
                val bq = Ledger.baseQty(m.quantity, m.factor)
                MvEntry(m.date, "${typeLabel(m.type)} #${m.invoiceId}", if (m.type == "sale" || m.type == "purchase_return") -bq else bq)
            }
            val sm = db.stockMoves().movesForItem(c.id).filter { it.type != "transfer" }.map { m ->
                MvEntry(m.date, moveTitle(m.type), if (m.type == "issue") -m.quantity else m.quantity)
            }
            (inv + sm).sortedBy { it.date }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (cur == null) {
            OutlinedTextField(query, { query = it }, label = { Text("ابحث عن صنف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            matches.forEach { m -> TextButton(onClick = { item = m }) { Text(m.name) } }
        } else {
            TextButton(onClick = { item = null; query = "" }) { Text("الصنف: ${cur.name} — تغيير") }
            val opening = cur.quantity - entries.sumOf { it.qty }
            var run = opening
            val table = entries.map { e -> run += e.qty; e to run }
            Text("الكمية الحالية: ${cur.quantity.money()} ${cur.unit}", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().background(BarBrush).padding(8.dp)) {
                listOf("التاريخ" to 1.3f, "البيان" to 2f, "وارد" to 0.9f, "صادر" to 0.9f, "الرصيد" to 1f).forEach { (t, w) ->
                    Text(t, Modifier.weight(w), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (opening != 0L) Row(Modifier.fillMaxWidth().background(Color(0xFFE3F0FF)).padding(8.dp)) {
                Text("", Modifier.weight(1.3f)); Text("رصيد سابق", Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                Text("", Modifier.weight(0.9f)); Text("", Modifier.weight(0.9f)); Text(opening.money(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            }
            table.forEach { (e, r) ->
                Row(Modifier.fillMaxWidth().background(Color.White).padding(8.dp)) {
                    Text(fmt.format(Date(e.date)), Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
                    Text(e.label, Modifier.weight(2f), style = MaterialTheme.typography.bodySmall)
                    Text(if (e.qty > 0) e.qty.money() else "", Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                    Text(if (e.qty < 0) (-e.qty).money() else "", Modifier.weight(0.9f), style = MaterialTheme.typography.bodySmall, color = Color(0xFFB3261E))
                    Text(r.money(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
            PdfActions(
                PdfSpec(
                    fileName = "item-movement-${cur.id}.pdf", title = "حركة صنف: ${cur.name}", subtitle = Prefs.header(ctx),
                    headers = listOf("التاريخ", "البيان", "وارد", "صادر", "الرصيد"),
                    rows = (if (opening != 0L) listOf(listOf("", "رصيد سابق", "", "", opening.money())) else emptyList()) +
                        table.map { (e, r) -> listOf(fmt.format(Date(e.date)), e.label, if (e.qty > 0) e.qty.money() else "", if (e.qty < 0) (-e.qty).money() else "", r.money()) },
                    footer = listOf("الكمية الحالية: ${cur.quantity.money()} ${cur.unit}"), weights = listOf(1.3f, 2f, 0.9f, 0.9f, 1f)
                )
            )
        }
    }
}

@Composable
fun DueScreen(db: BosDatabase, onOpen: (Long) -> Unit) {
    val list by db.customers().observeWithDue().collectAsStateWithLifecycle(emptyList())
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val now = System.currentTimeMillis()
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("لا توجد مواعيد سداد محددة. حدّد الموعد من كشف حساب العميل.")
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { c ->
                val due = c.dueDate ?: 0L
                val late = due < now
                Card(onClick = { onOpen(c.id) }, modifier = Modifier.fillMaxWidth(), colors = WHITE_CARD) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                (if (late) "متأخر منذ " else "الموعد: ") + fmt.format(Date(due)),
                                color = if (late) MaterialTheme.colorScheme.error else Color.Unspecified,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(c.balance.money(), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
