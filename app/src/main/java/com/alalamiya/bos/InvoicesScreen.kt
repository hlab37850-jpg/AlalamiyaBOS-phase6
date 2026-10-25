package com.alalamiya.bos

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Customer
import com.alalamiya.bos.data.Invoice
import com.alalamiya.bos.data.InvoiceLine
import com.alalamiya.bos.data.Item
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val TYPES = listOf("sale" to "بيع", "purchase" to "شراء", "sale_return" to "مرتجع بيع", "purchase_return" to "مرتجع شراء")
private fun groupTypes(g: String) = if (g == "sales") listOf("sale", "sale_return") else listOf("purchase", "purchase_return")
internal fun typeLabel(t: String) = TYPES.firstOrNull { it.first == t }?.second ?: t

private val GREEN = Color(0xFF2E7D32)
private val ORANGE = Color(0xFFEF6C00)
private val RED = Color(0xFFB3261E)

private fun typeColor(t: String): Color = when (t) {
    "sale" -> Color(0xFF1976D2)
    "sale_return" -> ORANGE
    "purchase" -> Color(0xFF00796B)
    else -> Color(0xFF8D6E63)
}

private fun isReturn(t: String) = t == "sale_return" || t == "purchase_return"

data class LineDraft(val item: Item, val qty: String, val price: String)

@Composable
private fun Pill(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

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
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US) }
    var selected by remember { mutableStateOf<Invoice?>(null) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }

    val todayStart = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val live = list.filter { !it.cancelled }
    fun signed(i: Invoice, v: Long) = if (isReturn(i.type)) -v else v
    val todayTotal = live.filter { it.date >= todayStart }.sumOf { signed(it, it.total) }
    val todayCount = live.count { it.date >= todayStart }
    val outstanding = live.sumOf { signed(it, it.total - it.paid) }

    val shown = remember(list, query, filter, names) {
        val q = query.trim()
        list.filter { inv ->
            val nameOk = q.isEmpty() || (names[inv.customerId] ?: "نقدي").contains(q) || inv.id.toString() == q
            val fOk = when (filter) {
                1 -> !inv.cancelled && inv.total == inv.paid
                2 -> !inv.cancelled && inv.total != inv.paid
                3 -> inv.cancelled
                else -> true
            }
            nameOk && fOk
        }
    }
    val noun = if (group == "sales") "المبيعات" else "المشتريات"

    Scaffold(floatingActionButton = {
        ExtendedFloatingActionButton(
            onClick = onNew, containerColor = BosGold, contentColor = Color.Black,
            icon = { Icon(Icons.Filled.Add, null) }, text = { Text("فاتورة جديدة", fontWeight = FontWeight.Bold) }
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(Brush.verticalGradient(listOf(BosBlueDark, BosBlue)))
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                HeaderStat("$noun اليوم", todayTotal.money(), Modifier.weight(1.3f))
                HeaderStat("عدد الفواتير", todayCount.toString(), Modifier.weight(1f))
                HeaderStat("المتبقي (آجل)", outstanding.money(), Modifier.weight(1.3f))
            }
            OutlinedTextField(
                query, { query = it }, label = { Text("بحث باسم العميل أو رقم الفاتورة") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("الكل", "نقدي", "آجل", "ملغاة").forEachIndexed { i, l ->
                    FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(l) })
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد فواتير") }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { it.id }) { inv ->
                        val tc = typeColor(inv.type)
                        Card(
                            onClick = { selected = inv }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(46.dp).clip(CircleShape).background(tc.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                                    Text("#${inv.id}", color = tc, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(names[inv.customerId] ?: "عميل نقدي", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("${typeLabel(inv.type)}  •  ${fmt.format(Date(inv.date))}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(inv.total.money(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    when {
                                        inv.cancelled -> Pill("ملغاة", RED)
                                        inv.total == inv.paid -> Pill("نقدي", GREEN)
                                        else -> Pill("آجل — متبقي ${(inv.total - inv.paid).money()}", ORANGE)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let { inv ->
        InvoiceDetail(db, inv, names[inv.customerId], itemNames, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)) { selected = null }
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
        footer = (if (inv.discount > 0) listOf("الخصم: ${inv.discount.money()}") else emptyList()) +
            listOf("الإجمالي: ${inv.total.money()}", "المدفوع: ${inv.paid.money()}", "المتبقي: ${(inv.total - inv.paid).money()}"),
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
                if (inv.discount > 0) Text("الخصم: ${inv.discount.money()}", style = MaterialTheme.typography.bodySmall)
                if (inv.currency != "YER") Text("العملة: ${inv.currency} (سعر الصرف ${inv.rate.money()})", style = MaterialTheme.typography.bodySmall)
                Text("الإجمالي: ${inv.total.money()}", style = MaterialTheme.typography.titleSmall)
                Text("المدفوع: ${inv.paid.money()}   المتبقي: ${(inv.total - inv.paid).money()}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PdfActions(spec); ThermalButton(spec) }
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
    val warehouses by db.warehouses().observeAll().collectAsStateWithLifecycle(emptyList())
    val currencies by db.currencies().observeAll().collectAsStateWithLifecycle(emptyList())
    val ledger = remember { Ledger(db) }
    val scope = rememberCoroutineScope()
    val dateText = remember { SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.US).format(Date()) }

    var type by remember { mutableStateOf(groupTypes(group).first()) }
    var partyQuery by remember { mutableStateOf("") }
    var party by remember { mutableStateOf<Customer?>(null) }
    val lines = remember { mutableStateListOf<LineDraft>() }
    var itemQuery by remember { mutableStateOf("") }
    var paidText by remember { mutableStateOf("") }
    var discountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var whId by remember { mutableStateOf<Long?>(null) }
    var curCode by remember { mutableStateOf("YER") }
    val effectiveWh = whId ?: warehouses.firstOrNull { it.isDefault }?.id ?: 1L
    val rate = currencies.firstOrNull { it.code == curCode }?.rate ?: 1000L

    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        val code = r.contents
        if (code != null) scope.launch {
            val found = withContext(Dispatchers.IO) { db.items().findByBarcode(code) }
            if (found == null) {
                error = "لا يوجد صنف بهذا الباركود"
            } else {
                val base = if (type == "sale" || type == "sale_return") found.salePrice else found.costPrice
                lines.add(LineDraft(found, "1", Ledger.fromBase(base, rate).money()))
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
    val gross = parsed.filterNotNull().sumOf { Ledger.lineTotal(it.quantity, it.price) }
    val discV: Long? = if (discountText.isBlank()) 0L else discountText.toMilli()
    val discOk = discV != null && discV in 0L..gross
    val net = gross - (discV ?: 0L).coerceIn(0L, gross)
    val paidV: Long? = if (paidText.isBlank()) net else paidText.toMilli()
    val paidOk = paidV != null && paidV in 0L..net && (party != null || paidV == net)
    val rest = if (paidV != null) net - paidV else net
    val tc = typeColor(type)

    val partyMatches = remember(partyQuery, customers) {
        if (partyQuery.isBlank()) emptyList() else customers.filter { it.name.contains(partyQuery.trim()) || it.phone.contains(partyQuery.trim()) }.take(5)
    }
    val itemMatches = remember(itemQuery, items) {
        if (itemQuery.isBlank()) emptyList() else items.filter { it.name.contains(itemQuery.trim()) }.take(6)
    }

    Scaffold(
        bottomBar = {
            Surface(shadowElevation = 12.dp, color = Color.White) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("الصافي بعد الخصم" + if (curCode != "YER") " ($curCode)" else "", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Text(net.money(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = tc)
                            if (rate != 1000L) Text("≈ ${Ledger.convert(net, rate).money()} بالعملة الأساسية", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("المتبقي", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Text(rest.money(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = if (rest > 0) ORANGE else GREEN)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            discountText, { discountText = it }, label = { Text("الخصم") }, singleLine = true,
                            isError = !discOk, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            paidText, { paidText = it }, label = { Text("المدفوع (فارغ = كامل)") }, singleLine = true,
                            isError = !paidOk, modifier = Modifier.weight(1f)
                        )
                    }
                    if (paidV != null && paidV in 0L..net && paidV < net && party == null) {
                        Text("الفاتورة الآجلة تحتاج اختيار عميل", color = RED, style = MaterialTheme.typography.bodySmall)
                    }
                    error?.let { Text(it, color = RED, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            modifier = Modifier.weight(1f).height(48.dp), enabled = allValid && paidOk && discOk && !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = BosGold, contentColor = Color.Black),
                            onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        withContext(Dispatchers.IO) {
                                            ledger.postInvoice(
                                                type, party?.id, parsed.filterNotNull(), if (paidText.isBlank()) null else paidV,
                                                discV ?: 0L, note.trim(), effectiveWh, curCode, rate
                                            )
                                        }
                                        onClose()
                                    } catch (e: Exception) {
                                        error = "لم تُحفظ الفاتورة: ${e.message}"
                                    }
                                    busy = false
                                }
                            }
                        ) { Text("حفظ الفاتورة", fontWeight = FontWeight.Bold) }
                        TextButton(onClick = onClose) { Text("إلغاء") }
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(Brush.verticalGradient(listOf(BosBlueDark, tc)))
                    .padding(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${typeLabel(type)} — فاتورة جديدة", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(dateText, color = Color(0xFFBBD7F7), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TYPES.filter { it.first in groupTypes(group) }.forEach { (k, v) ->
                            FilterChip(selected = type == k, onClick = { type = k; lines.clear(); error = null }, label = { Text(v) })
                        }
                    }
                    if (warehouses.size > 1) {
                        Text("المستودع", color = Color(0xFFBBD7F7), style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            warehouses.forEach { w -> FilterChip(selected = effectiveWh == w.id, onClick = { whId = w.id }, label = { Text(w.name) }) }
                        }
                    }
                    if (currencies.size > 1) {
                        Text("العملة", color = Color(0xFFBBD7F7), style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            currencies.forEach { c ->
                                FilterChip(selected = curCode == c.code, onClick = { if (curCode != c.code) { curCode = c.code; lines.clear(); error = null } }, label = { Text(c.code) })
                            }
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val cur = party
                        if (cur == null) {
                            OutlinedTextField(partyQuery, { partyQuery = it }, label = { Text(if (group == "sales") "العميل (اتركه فارغًا للنقدي)" else "المورد (اتركه فارغًا للنقدي)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            partyMatches.forEach { c -> TextButton(onClick = { party = c }) { Text("${c.name}  (${c.balance.money()})") } }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(cur.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("الرصيد الحالي: ${cur.balance.money()}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                TextButton(onClick = { party = null }) { Text("تغيير") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = paidText.isBlank(), onClick = { paidText = "" }, label = { Text("نقدي") })
                            FilterChip(selected = paidText == "0", onClick = { paidText = "0" }, label = { Text("آجل") })
                        }
                    }
                }

                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("الأصناف (${lines.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(itemQuery, { itemQuery = it }, label = { Text("ابحث عن صنف لإضافته") }, singleLine = true, modifier = Modifier.weight(1f))
                            TextButton(onClick = { scanner.launch(ScanOptions().setPrompt("امسح باركود الصنف")) }) { Text("مسح") }
                        }
                        itemMatches.forEach { it0 ->
                            val base = if (type == "sale" || type == "sale_return") it0.salePrice else it0.costPrice
                            val price = Ledger.fromBase(base, rate)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF1F5FB)),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = {
                                    lines.add(LineDraft(it0, "1", price.money()))
                                    itemQuery = ""
                                }, modifier = Modifier.weight(1f)) { Text(it0.name, maxLines = 1) }
                                Text("مخزون ${it0.quantity.money()} • ${price.money()}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp))
                            }
                        }
                        if (lines.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().background(BosBlue).padding(8.dp)) {
                                Text("م", Modifier.width(24.dp), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                Text("الصنف", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                Text("الإجمالي", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(40.dp))
                            }
                        }
                        lines.forEachIndexed { idx, l ->
                            val lq = l.qty.toMilli()
                            val lp = l.price.toMilli()
                            val lt = if (lq != null && lp != null && lq > 0 && lp >= 0) Ledger.lineTotal(lq, lp) else null
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFFF7F9FD)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${idx + 1}", Modifier.width(24.dp), style = MaterialTheme.typography.labelMedium)
                                    Text(l.item.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(lt?.money() ?: "—", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = tc)
                                    IconButton(onClick = { lines.removeAt(idx) }) { Icon(Icons.Filled.Delete, "حذف", tint = RED) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(l.qty, { lines[idx] = l.copy(qty = it) }, label = { Text("الكمية") }, singleLine = true, isError = lq == null || lq <= 0, modifier = Modifier.weight(1f))
                                    OutlinedTextField(l.price, { lines[idx] = l.copy(price = it) }, label = { Text("السعر") }, singleLine = true, isError = lp == null || lp < 0, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        if (lines.isEmpty()) Text("لم تُضف أصناف بعد. ابحث بالاسم أو امسح الباركود.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }

                OutlinedTextField(note, { note = it }, label = { Text("ملاحظة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
