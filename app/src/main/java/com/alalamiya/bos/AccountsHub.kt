package com.alalamiya.bos

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.alalamiya.bos.data.Customer
import com.alalamiya.bos.data.OperationLog
import com.alalamiya.bos.data.StmtLine
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

internal fun balText(b: Long): String = money(abs(b)) + if (b > 0) " عليه" else if (b < 0) " له" else ""
private fun money(x: Long) = x.money()

internal fun typeNameAr(type: String, code: String): String = when {
    code.startsWith("11") -> "نقدية"
    type == "expense" -> "مصروفات"
    type == "income" -> "إيرادات"
    type == "liability" -> "التزامات"
    type == "equity" -> "حقوق ملكية"
    type == "asset" -> "أصول"
    else -> "أخرى"
}

/** نافذة اختيار حساب: أطراف (عملاء/موردون) وحسابات دليل الحسابات. */
@Composable
fun AccountPickerDialog(
    db: BosDatabase, exclude: Set<String> = setOf("1200", "2100"), onDismiss: () -> Unit, onPick: (AccRef, String) -> Unit
) {
    val parties by db.customers().observeAll().collectAsStateWithLifecycle(emptyList())
    val accounts by db.accounts().observeAllAccounts().collectAsStateWithLifecycle(emptyList())
    var q by remember { mutableStateOf("") }
    val pList = remember(parties, q) { parties.filter { q.isBlank() || it.name.contains(q.trim()) }.take(60) }
    val aList = remember(accounts, q, exclude) { accounts.filter { it.code !in exclude && (q.isBlank() || it.name.contains(q.trim())) }.take(60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اختيار حساب") },
        text = {
            Column {
                UnderlineField(q, { q = it }, "بحث")
                LazyColumn(Modifier.height(340.dp)) {
                    items(pList, key = { "p${it.id}" }) { p ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(AccRef(partyId = p.id), p.name) }.padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(p.name, Modifier.weight(1f), maxLines = 1)
                            Text(if (p.kind == 1) "مورد" else "عميل", color = Color.Gray)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
                    }
                    items(aList, key = { "a${it.id}" }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(AccRef(accountId = a.id), a.name) }.padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(a.name, Modifier.weight(1f), maxLines = 1)
                            Text(typeNameAr(a.type, a.code), color = Color.Gray)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

private data class AccRow(val key: String, val name: String, val sub: String, val bal: Long, val party: Long?, val acc: Long?, val group: Int)

@Composable
fun AccountsScreen(db: BosDatabase, onParty: (Long) -> Unit, onAccount: (Long) -> Unit, onAdd: () -> Unit) {
    val parties by db.customers().observeAll().collectAsStateWithLifecycle(emptyList())
    val accs by db.accounts().observeAccBalances().collectAsStateWithLifecycle(emptyList())
    var filter by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    val labels = listOf("الكل", "العملاء", "الموردون", "النقدية", "المصروفات", "الإيرادات", "أخرى")
    val rows = remember(parties, accs, filter, q) {
        val all = parties.map { AccRow("p${it.id}", it.name, if (it.kind == 1) "مورد" else "عميل", it.balance, it.id, null, if (it.kind == 1) 2 else 1) } +
            accs.filter { it.code != "1200" && it.code != "2100" }.map { a ->
                val g = when {
                    a.code.startsWith("11") -> 3
                    a.type == "expense" -> 4
                    a.type == "income" -> 5
                    else -> 6
                }
                AccRow("a${a.id}", a.name, typeNameAr(a.type, a.code), a.debit - a.credit, null, a.id, g)
            }
        val s = q.trim()
        all.filter { (filter == 0 || it.group == filter) && (s.isEmpty() || it.name.contains(s)) }
    }
    Scaffold(floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, "إضافة حساب") } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEachIndexed { i, l -> FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(l) }) }
            }
            OutlinedTextField(q, { q = it }, label = { Text("بحث بالاسم") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.key }) { r ->
                    Card(
                        onClick = { if (r.party != null) onParty(r.party) else r.acc?.let(onAccount) },
                        modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(r.sub, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Text(
                                if (r.bal == 0L) "0" else balText(r.bal), style = MaterialTheme.typography.titleSmall,
                                color = if (r.bal > 0) Color(0xFFB3261E) else if (r.bal < 0) Color(0xFF2E7D32) else Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddAccountScreen(db: BosDatabase, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var vat by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    val types = listOf("عميل", "مورد", "نقدية / بنك", "مصروفات", "إيرادات", "أخرى")
    val isParty = type <= 1
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("نوع الحساب", color = Color.Gray)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEachIndexed { i, l -> FilterChip(selected = type == i, onClick = { type = i }, label = { Text(l) }) }
        }
        UnderlineField(name, { name = it }, "اسم الحساب")
        if (isParty) {
            UnderlineField(phone, { phone = it }, "الهاتف")
            UnderlineField(address, { address = it }, "العنوان")
            UnderlineField(vat, { vat = it }, "الرقم الضريبي")
            LookupField(db, 0, "التصنيف", group) { group = it }
        } else {
            Text(
                when (type) {
                    2 -> "يظهر في قائمة الصناديق عند السندات والفواتير."
                    3 -> "يظهر في المصروفات وقائمة الدخل."
                    4 -> "يظهر في الإيرادات وقائمة الدخل."
                    else -> "حساب عام في دليل الحسابات."
                }, style = MaterialTheme.typography.bodySmall, color = Color.Gray
            )
        }
        Button(enabled = name.isNotBlank(), onClick = {
            scope.launch {
                val msg = withContext(Dispatchers.IO) {
                    try {
                        val n = name.trim()
                        if (isParty) {
                            if (db.customers().findByName(n) != null) return@withContext "يوجد حساب بنفس الاسم"
                            db.customers().insert(Customer(name = n, phone = phone.trim(), address = address.trim(), kind = type, vatNo = vat.trim(), groupName = group.trim()))
                        } else {
                            val (kind, code) = when (type) {
                                2 -> "asset" to ((db.accounts().maxCode("11%") ?: 1100) + 1)
                                3 -> "expense" to ((db.accounts().maxExpenseCode() ?: 6000) + 1)
                                4 -> "income" to ((db.accounts().maxCode("7%") ?: 7000) + 1)
                                else -> "other" to ((db.accounts().maxCode("8%") ?: 8000) + 1)
                            }
                            if (db.accounts().findByNameAndType(n, kind) != null) return@withContext "يوجد حساب بنفس الاسم"
                            db.accounts().insert(Account(code = code.toString(), name = n, type = kind))
                        }
                        db.log().insert(OperationLog(action = "add_account", details = n))
                        null
                    } catch (e: Exception) { e.message ?: "تعذّر الحفظ" }
                }
                if (msg == null) { Toast.makeText(ctx, "تمت الإضافة", Toast.LENGTH_SHORT).show(); onDone() } else Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }) { Text("حفظ") }
    }
}

/** جدول كشف حساب لحساب من دليل الحسابات مع تصدير PDF. */
@Composable
fun AccountStatementBody(db: BosDatabase, accId: Long) {
    val ctx = LocalContext.current
    var acc by remember(accId) { mutableStateOf<Account?>(null) }
    var rows by remember(accId) { mutableStateOf<List<StmtLine>?>(null) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val balances by db.accounts().observeAccBalances().collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(accId, balances) {
        withContext(Dispatchers.IO) { acc = db.accounts().findById(accId); rows = db.journal().statementFor(accId) }
    }
    val list = rows
    val a = acc
    if (a == null || list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("جارٍ التحميل...") }
        return
    }
    var run = 0L
    val withRun = list.map { run += it.debit - it.credit; it to run }
    val spec = PdfSpec(
        fileName = "account-${a.code}.pdf", title = "كشف حساب: ${a.name}", subtitle = Prefs.header(ctx),
        headers = listOf("التاريخ", "البيان", "مدين", "دائن", "الرصيد"),
        rows = withRun.map { (l, r) -> listOf(fmt.format(Date(l.date)), l.description, if (l.debit > 0) l.debit.money() else "", if (l.credit > 0) l.credit.money() else "", balText(r)) },
        footer = listOf("الرصيد: ${balText(run)}"), weights = listOf(1.3f, 2.2f, 1f, 1f, 1.4f)
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(Modifier.fillMaxWidth().background(BarBrush).padding(8.dp)) {
                listOf("التاريخ" to 1.3f, "البيان" to 2.2f, "مدين" to 1f, "دائن" to 1f, "الرصيد" to 1.4f).forEach { (t, w) ->
                    Text(t, Modifier.weight(w), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        items(withRun.size) { i ->
            val (l, r) = withRun[i]
            Row(Modifier.fillMaxWidth().background(Color.White).padding(8.dp)) {
                Text(fmt.format(Date(l.date)), Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
                Text(l.description, Modifier.weight(2.2f), style = MaterialTheme.typography.bodySmall)
                Text(if (l.debit > 0) l.debit.money() else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(if (l.credit > 0) l.credit.money() else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(balText(r), Modifier.weight(1.4f), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الرصيد: ${balText(run)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                PdfActions(spec)
            }
        }
    }
}

@Composable
fun AccountStatementScreen(db: BosDatabase, accId: Long) = AccountStatementBody(db, accId)

@Composable
fun CashScreen(db: BosDatabase) {
    val cash by db.accounts().observeCashAccounts().collectAsStateWithLifecycle(emptyList())
    var sel by remember { mutableStateOf<Long?>(null) }
    if (cash.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد حركة صندوق بعد") }
        return
    }
    val cur = sel ?: cash.first().id
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cash.forEach { c -> FilterChip(selected = cur == c.id, onClick = { sel = c.id }, label = { Text(c.name) }) }
        }
        Box(Modifier.weight(1f)) { AccountStatementBody(db, cur) }
    }
}

@Composable
fun ChartScreen(db: BosDatabase) {
    val accs by db.accounts().observeAccBalances().collectAsStateWithLifecycle(emptyList())
    val groups = listOf('1' to "الأصول", '2' to "الالتزامات", '3' to "حقوق الملكية", '4' to "الإيرادات", '5' to "المصروفات والتكاليف", '6' to "المصروفات", '7' to "إيرادات أخرى", '8' to "حسابات أخرى")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.forEach { (d, title) ->
            val inGroup = accs.filter { it.code.firstOrNull() == d }
            if (inGroup.isNotEmpty()) {
                item(key = "h$d") { Text(title, Modifier.fillMaxWidth().background(BarBrush).padding(8.dp), color = Color.White, fontWeight = FontWeight.Bold) }
                items(inGroup, key = { it.id }) { a ->
                    Row(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${a.code}  ${a.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(balText(a.debit - a.credit), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun LimitsScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val parties by db.customers().observeAll().collectAsStateWithLifecycle(emptyList())
    var q by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf<Customer?>(null) }
    var dText by remember { mutableStateOf("") }
    var cText by remember { mutableStateOf("") }
    val matches = remember(q, parties) { if (q.isBlank()) emptyList() else parties.filter { it.name.contains(q.trim()) }.take(6) }
    val withLimits = parties.filter { it.debitLimit > 0 || it.creditLimit > 0 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("سقف الحساب يمنع تجاوز المديونية أو الدائنية عند الفواتير والسندات والقيود. اترك الحقل صفرًا لعدم وضع سقف.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        val cur = sel
        if (cur == null) {
            UnderlineField(q, { q = it }, "اسم الحساب")
            matches.forEach { m -> TextButton(onClick = { sel = m; dText = m.debitLimit.money(); cText = m.creditLimit.money() }) { Text(m.name) } }
        } else {
            TextButton(onClick = { sel = null; q = "" }) { Text("الحساب: ${cur.name} — تغيير") }
            Text("الرصيد الحالي: ${balText(cur.balance)}")
            OutlinedTextField(dText, { dText = it }, label = { Text("أقصى مديونية (عليه)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cText, { cText = it }, label = { Text("أقصى دائنية (له)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val d = dText.toMilli()
            val c = cText.toMilli()
            Button(enabled = d != null && c != null && d >= 0 && c >= 0, onClick = {
                scope.launch(Dispatchers.IO) { db.customers().update(cur.copy(debitLimit = d ?: 0, creditLimit = c ?: 0)) }
                Toast.makeText(ctx, "تم حفظ السقف", Toast.LENGTH_SHORT).show()
                sel = null; q = ""
            }) { Text("حفظ السقف") }
        }
        if (withLimits.isNotEmpty()) {
            Text("الحسابات ذات السقف", fontWeight = FontWeight.Bold)
            withLimits.forEach {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(it.name, fontWeight = FontWeight.Bold)
                        Text("عليه ≤ ${if (it.debitLimit > 0) it.debitLimit.money() else "—"}   |   له ≤ ${if (it.creditLimit > 0) it.creditLimit.money() else "—"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
