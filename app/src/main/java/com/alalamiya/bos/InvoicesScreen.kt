package com.alalamiya.bos

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.Account
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val TYPES = listOf("sale" to "بيع", "purchase" to "شراء", "sale_return" to "مرتجع بيع", "purchase_return" to "مرتجع شراء")
private fun groupTypes(g: String) = if (g == "sales") listOf("sale", "sale_return") else listOf("purchase", "purchase_return")
internal fun typeLabel(t: String) = TYPES.firstOrNull { it.first == t }?.second ?: t

private val ORANGE = Color(0xFFEF6C00)
private val RED = Color(0xFFB3261E)
private val INDIGO = Color(0xFF5C6BC0)

data class LineDraft(val item: Item, val qty: String, val price: String)

private fun pickDate(ctx: android.content.Context, cur: Long?, endOfDay: Boolean, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance()
    cur?.let { c.timeInMillis = it }
    DatePickerDialog(ctx, { _, y, m, d ->
        val t = Calendar.getInstance()
        if (endOfDay) t.set(y, m, d, 23, 59, 59) else t.set(y, m, d, 0, 0, 0)
        t.set(Calendar.MILLISECOND, 0)
        onPicked(t.timeInMillis)
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

@Composable
fun InvoicesScreen(db: BosDatabase, group: String, onBack: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    BackHandler(enabled = editing) { editing = false }
    if (editing) InvoiceEditor(db, group) { editing = false } else InvoiceList(db, group, onBack) { editing = true }
}

// ───────────────────────── قائمة المبيعات / المشتريات ─────────────────────────

@Composable
private fun InvoiceList(db: BosDatabase, group: String, onBack: () -> Unit, onNew: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all by db.invoices().observeRecent().collectAsStateWithLifecycle(emptyList())
    val customers by db.customers().observeAll().collectAsStateWithLifecycle(emptyList())
    val allItems by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    val names = remember(customers) { customers.associate { it.id to it.name } }
    val itemNames = remember(allItems) { allItems.associate { it.id to it.name } }
    val fmtDate = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val noun = if (group == "sales") "المبيعات" else "المشتريات"

    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var sortKey by remember { mutableIntStateOf(1) }
    var asc by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var showAdv by remember { mutableStateOf(false) }
    var advName by remember { mutableStateOf("") }
    var advStatus by remember { mutableIntStateOf(0) }
    var advFrom by remember { mutableStateOf<Long?>(null) }
    var advTo by remember { mutableStateOf<Long?>(null) }
    var showPdf by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Invoice?>(null) }
    BackHandler(enabled = searchOpen) { searchOpen = false; query = "" }

    val shown = remember(all, group, query, sortKey, asc, advName, advStatus, advFrom, advTo, names) {
        val q = query.trim()
        val an = advName.trim()
        val from = advFrom
        val to = advTo
        val base = all.filter { it.type in groupTypes(group) }.filter { inv ->
            val nm = names[inv.customerId] ?: "نقدي"
            val statusOk = when (advStatus) {
                1 -> !inv.cancelled && inv.total == inv.paid
                2 -> !inv.cancelled && inv.total != inv.paid
                3 -> inv.cancelled
                else -> true
            }
            (q.isEmpty() || nm.contains(q) || inv.id.toString() == q) &&
                (an.isEmpty() || nm.contains(an)) && statusOk &&
                (from == null || inv.date >= from) && (to == null || inv.date <= to)
        }
        val cmp: Comparator<Invoice> = when (sortKey) {
            0 -> compareBy<Invoice> { it.id }
            1 -> compareBy<Invoice> { it.date }
            2 -> compareBy<Invoice> { names[it.customerId] ?: "" }
            else -> compareBy<Invoice> { it.total }
        }
        if (asc) base.sortedWith(cmp) else base.sortedWith(cmp.reversed())
    }
    val grandTotal = shown.filter { !it.cancelled }.sumOf { it.total }

    fun listSpec(thermal: Boolean) = PdfSpec(
        fileName = "invoices-list.pdf",
        title = "قائمة $noun",
        subtitle = Prefs.header(ctx),
        headers = listOf("رقم", "التاريخ", "الإسم", "المبلغ"),
        rows = shown.map { i ->
            val nm = names[i.customerId] ?: "نقدي"
            if (thermal) listOf("#${i.id} $nm", "", "", i.total.money())
            else listOf(i.id.toString(), fmtDate.format(Date(i.date)), nm, i.total.money())
        },
        footer = listOf("عدد الفواتير: ${shown.size}", "الإجمالي: ${grandTotal.money()}"),
        weights = listOf(0.7f, 1.3f, 1.6f, 1f)
    )

    fun sortBy(k: Int) { if (sortKey == k) asc = !asc else { sortKey = k; asc = k == 2 } }

    Column(Modifier.fillMaxSize().background(RefBg)) {
        RefBar(
            onBack = { if (searchOpen) { searchOpen = false; query = "" } else onBack() },
            middle = {
                if (searchOpen) {
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Left),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                } else {
                    Text(
                        "قائمة $noun", Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White,
                        fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Left, maxLines = 1
                    )
                }
            },
            actions = {
                IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                    Icon(Icons.Filled.Search, "بحث", tint = Color.White)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFFD32F2F)).clickable { showPdf = true }, contentAlignment = Alignment.Center) {
                    Text("PDF", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "المزيد", tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("بحث متقدم", Modifier.fillMaxWidth(), textAlign = TextAlign.Right, fontSize = 17.sp) }, onClick = { menu = false; showAdv = true })
                        DropdownMenuItem(text = { Text("طباعة الفواتير", Modifier.fillMaxWidth(), textAlign = TextAlign.Right, fontSize = 17.sp) }, onClick = {
                            menu = false
                            scope.launch {
                                val f = withContext(Dispatchers.IO) {
                                    File(ctx.cacheDir, "print.pdf").also { out -> out.outputStream().use { PdfExport.write(ctx, it, listSpec(false)) } }
                                }
                                PdfExport.printFile(ctx, f, "قائمة $noun")
                            }
                        })
                        DropdownMenuItem(text = { Text("طباعة حرارية للفواتير", Modifier.fillMaxWidth(), textAlign = TextAlign.Right, fontSize = 17.sp) }, onClick = {
                            menu = false
                            scope.launch {
                                val m = withContext(Dispatchers.IO) { Thermal.print(ctx, listSpec(true)) }
                                Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
                            }
                        })
                    }
                }
            }
        )

        // رأس الجدول
        Row(Modifier.fillMaxWidth().background(BarBrush).padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("رقم", Modifier.weight(0.7f).clickable { sortBy(0) }, color = Color.White, fontSize = 17.sp, textDecoration = TextDecoration.Underline)
            Row(Modifier.weight(1.3f).clickable { sortBy(1) }, verticalAlignment = Alignment.CenterVertically) {
                Text("التاريخ", color = Color.White, fontSize = 17.sp, textDecoration = TextDecoration.Underline)
                Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
            }
            Text("الإسم", Modifier.weight(1.6f).clickable { sortBy(2) }, color = Color.White, fontSize = 17.sp, textDecoration = TextDecoration.Underline)
            Text("المبلغ", Modifier.weight(1f).clickable { sortBy(3) }, color = Color.White, fontSize = 17.sp, textDecoration = TextDecoration.Underline)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (shown.isEmpty()) {
                Text("لا توجد نتائج...", Modifier.align(Alignment.Center), color = INDIGO, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.id }) { inv ->
                        val remaining = inv.total - inv.paid
                        val tint = if (inv.cancelled) Color.Gray else Color.Black
                        val deco = if (inv.cancelled) TextDecoration.LineThrough else TextDecoration.None
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = inv }.padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(inv.id.toString(), Modifier.weight(0.7f), color = tint, fontSize = 16.sp, textDecoration = deco)
                            Text(fmtDate.format(Date(inv.date)), Modifier.weight(1.3f), color = tint, fontSize = 16.sp, textDecoration = deco)
                            Text(names[inv.customerId] ?: "نقدي", Modifier.weight(1.6f), color = tint, fontSize = 16.sp, textDecoration = deco, maxLines = 1)
                            Text(
                                inv.total.money(), Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, textDecoration = deco,
                                color = if (!inv.cancelled && remaining > 0) ORANGE else tint
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE0E0E0)))
                    }
                }
            }
        }

        // الشريط السفلي مع زر الإضافة
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.fillMaxWidth().height(60.dp).background(BarBrush), contentAlignment = Alignment.Center) {
                Text(
                    if (shown.isEmpty()) "........." else "${shown.size} فاتورة  •  ${grandTotal.money()}",
                    color = Color.White, fontSize = 17.sp
                )
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { PlusCircle(onNew, filled = true) }
            }
        }
    }

    PdfPreviewHost(listSpec(false), showPdf) { showPdf = false }

    if (showAdv) {
        AlertDialog(
            onDismissRequest = { showAdv = false },
            title = { Text("بحث متقدم") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    UnderlineField(advName, { advName = it }, if (group == "sales") "اسم العميل" else "اسم المورد")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("الكل", "نقدي", "آجل", "ملغاة").forEachIndexed { i, l ->
                            FilterChip(selected = advStatus == i, onClick = { advStatus = i }, label = { Text(l) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("من تاريخ")
                        OutlinedButton(onClick = { pickDate(ctx, advFrom, false) { advFrom = it } }) { Text(advFrom?.let { fmtDate.format(Date(it)) } ?: "—") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("إلى تاريخ")
                        OutlinedButton(onClick = { pickDate(ctx, advTo, true) { advTo = it } }) { Text(advTo?.let { fmtDate.format(Date(it)) } ?: "—") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAdv = false }) { Text("تطبيق") } },
            dismissButton = {
                TextButton(onClick = { advName = ""; advStatus = 0; advFrom = null; advTo = null; showAdv = false }) { Text("مسح الفلاتر") }
            }
        )
    }

    selected?.let { inv ->
        InvoiceDetail(db, inv, names[inv.customerId], itemNames, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)) { selected = null }
    }
}

// ───────────────────────── تفاصيل الفاتورة ─────────────────────────

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
    val extras = buildList {
        if (inv.discount > 0) add("الخصم: ${inv.discount.money()}")
        if (inv.tax > 0) add("الضريبة: ${inv.tax.money()}")
        if (inv.fees > 0) add("رسوم أخرى: ${inv.fees.money()}")
    }
    val spec = PdfSpec(
        fileName = "invoice-${inv.id}.pdf",
        title = title,
        subtitle = Prefs.header(ctx) + "\n" + (party ?: "نقدي") + " — " + fmt.format(Date(inv.date)),
        headers = listOf("الصنف", "الكمية", "السعر", "الإجمالي"),
        rows = rows,
        footer = extras + listOf("الإجمالي: ${inv.total.money()}", "المدفوع: ${inv.paid.money()}", "المتبقي: ${(inv.total - inv.paid).money()}"),
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
                extras.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
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

// ───────────────────────── شاشة الفاتورة (مطابقة للتطبيق المرجعي) ─────────────────────────

@Composable
private fun InvoiceEditor(db: BosDatabase, group: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    val customers by db.customers().observeAll().collectAsStateWithLifecycle(emptyList())
    val warehouses by db.warehouses().observeAll().collectAsStateWithLifecycle(emptyList())
    val currencies by db.currencies().observeAll().collectAsStateWithLifecycle(emptyList())
    val cashAccounts by db.accounts().observeCashAccounts().collectAsStateWithLifecycle(emptyList())
    val ledger = remember { Ledger(db) }
    val sales = group == "sales"

    var isReturn by remember { mutableStateOf(false) }
    var cash by remember { mutableStateOf(true) }
    val type = if (sales) (if (isReturn) "sale_return" else "sale") else (if (isReturn) "purchase_return" else "purchase")
    val title = (if (isReturn) "مرتجع " else "") + (if (sales) "بيع" else "شراء") + (if (cash) " نقدي" else " آجل")

    var partyQuery by remember { mutableStateOf("") }
    var party by remember { mutableStateOf<Customer?>(null) }
    val lines = remember { mutableStateListOf<LineDraft>() }
    var itemQuery by remember { mutableStateOf("") }
    var discountText by remember { mutableStateOf("") }
    var taxText by remember { mutableStateOf("") }
    var taxPercent by remember { mutableStateOf(true) }
    var feesText by remember { mutableStateOf("") }
    var paidText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var whId by remember { mutableStateOf<Long?>(null) }
    var curCode by remember { mutableStateOf("YER") }
    var cashCode by remember { mutableStateOf("1100") }
    var busy by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    var showAddCash by remember { mutableStateOf(false) }
    var nextNo by remember { mutableStateOf(1L) }
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

    LaunchedEffect(Unit) { nextNo = withContext(Dispatchers.IO) { db.invoices().nextNumber() } }

    val effectiveWh = whId ?: warehouses.firstOrNull { it.isDefault }?.id ?: 1L
    val whName = warehouses.firstOrNull { it.id == effectiveWh }?.name ?: "المستودع الرئيسي"
    val rate = currencies.firstOrNull { it.code == curCode }?.rate ?: 1000L
    val cashOptions = cashAccounts.ifEmpty { listOf(Account(code = "1100", name = "الصندوق", type = "asset")) }
    val cashName = cashOptions.firstOrNull { it.code == cashCode }?.name ?: cashOptions.first().name

    fun addLine(it0: Item) {
        val base = if (sales) it0.salePrice else it0.costPrice
        lines.add(LineDraft(it0, "1", Ledger.fromBase(base, rate).money()))
        itemQuery = ""
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { r ->
        val code = r.contents
        if (code != null) scope.launch {
            val found = withContext(Dispatchers.IO) { db.items().findByBarcode(code) }
            if (found == null) Toast.makeText(ctx, "لا يوجد صنف بهذا الباركود", Toast.LENGTH_SHORT).show() else addLine(found)
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
    val afterDisc = gross - (discV ?: 0L).coerceIn(0L, gross)
    val taxIn: Long? = if (taxText.isBlank()) 0L else taxText.toMilli()
    val taxOk = taxIn != null && taxIn >= 0 && (!taxPercent || taxIn <= 100_000L)
    val taxAmt = if (taxIn == null || !taxOk) 0L else if (taxPercent) Ledger.percentOf(afterDisc, taxIn) else taxIn
    val feesV: Long? = if (feesText.isBlank()) 0L else feesText.toMilli()
    val feesOk = feesV != null && feesV >= 0
    val fees = if (feesOk) feesV ?: 0L else 0L
    val net = afterDisc + taxAmt + fees
    val paidV: Long = if (cash) net else if (paidText.isBlank()) 0L else (paidText.toMilli() ?: -1L)
    val paidOk = paidV in 0L..net && (party != null || paidV == net)
    val rest = if (paidOk) net - paidV else net

    val partyMatches = remember(partyQuery, party, customers) {
        if (party != null || partyQuery.isBlank()) emptyList()
        else customers.filter { it.name.contains(partyQuery.trim()) || it.phone.contains(partyQuery.trim()) }.take(5)
    }
    val itemMatches = remember(itemQuery, items) {
        if (itemQuery.isBlank()) emptyList() else items.filter { it.name.contains(itemQuery.trim()) || it.barcode == itemQuery.trim() }.take(6)
    }

    fun save() {
        val problem = when {
            lines.isEmpty() -> "أضف صنفًا واحدًا على الأقل"
            !allValid -> "تحقق من الكمية والسعر في البنود"
            !discOk -> "قيمة الخصم غير صحيحة"
            !taxOk -> "قيمة الضريبة غير صحيحة"
            !feesOk -> "قيمة الرسوم غير صحيحة"
            !paidOk -> if (party == null && !cash) "الفاتورة الآجلة تحتاج اختيار ${if (sales) "عميل" else "مورد"}" else "المبلغ المدفوع غير صحيح"
            else -> null
        }
        if (problem != null) { Toast.makeText(ctx, problem, Toast.LENGTH_SHORT).show(); return }
        if (busy) return
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    ledger.postInvoice(
                        type, party?.id, parsed.filterNotNull(), if (cash) null else paidV, discV ?: 0L,
                        taxAmt, fees, note.trim(), effectiveWh, curCode, rate, date, cashCode
                    )
                }
                onClose()
            } catch (e: Exception) {
                Toast.makeText(ctx, "لم تُحفظ الفاتورة: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().background(RefBg)) {
        RefBar(
            onBack = onClose,
            middle = {
                Text(
                    title, Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Left, maxLines = 1
                )
            },
            actions = {
                IconButton(onClick = { save() }) { Icon(Icons.Filled.Save, "حفظ", tint = Color.White, modifier = Modifier.size(30.dp)) }
            }
        )

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RefCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = cash, onCheckedChange = { cash = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = RefPink, checkedTrackColor = RefPink.copy(alpha = 0.45f))
                        )
                        Text(if (cash) "نقد" else "آجل", fontSize = 17.sp, modifier = Modifier.padding(horizontal = 6.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Spinner(whName, warehouses.map { it.name }, { whId = warehouses[it].id })
                        }
                        Text("مرتجع", fontSize = 17.sp)
                        Checkbox(checked = isReturn, onCheckedChange = { isReturn = it }, colors = CheckboxDefaults.colors(checkedColor = RefPink))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (cash) {
                                Spinner(
                                    cashName, cashOptions.map { it.name }, { cashCode = cashOptions[it].code },
                                    extra = listOf("+ إضافة صندوق / بنك"), onExtra = { showAddCash = true }
                                )
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            Spinner(
                                if (curCode == "YER") "محلي" else curCode,
                                currencies.map { if (it.code == "YER") "محلي" else it.code },
                                { i ->
                                    val c = currencies[i].code
                                    if (c != curCode) { curCode = c; lines.clear() }
                                },
                                bold = true
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                        UnderlineField(
                            partyQuery, { partyQuery = it; party = null }, if (sales) "العميل" else "المورد",
                            Modifier.weight(1f)
                        )
                        Text(
                            dateFmt.format(Date(date)),
                            Modifier.padding(start = 14.dp, bottom = 8.dp).clickable { pickDate(ctx, date, false) { date = it } },
                            fontSize = 18.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    party?.let { Text("الرصيد الحالي: ${it.balance.money()}", fontSize = 14.sp, color = Color.Gray) }
                    partyMatches.forEach { c ->
                        Text(
                            "${c.name}   (${c.balance.money()})",
                            Modifier.fillMaxWidth().background(Color(0xFFF1F5FB)).clickable { party = c; partyQuery = c.name }.padding(10.dp),
                            fontSize = 16.sp
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                        UnderlineField("رقم#$nextNo", {}, "رقم#", Modifier.weight(0.6f), readOnly = true)
                        UnderlineField(note, { note = it }, "ملاحظات", Modifier.weight(1.4f))
                    }
                }
            }

            RefCard {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        UnderlineField(itemQuery, { itemQuery = it }, "إكتب اسم الصنف", Modifier.weight(1f), centerHint = true)
                        IconButton(onClick = { scanner.launch(ScanOptions().setPrompt("امسح باركود الصنف")) }) {
                            Icon(Icons.Filled.QrCodeScanner, "مسح باركود", tint = RefBlue)
                        }
                        PlusCircle({ showPicker = true; pickerQuery = "" })
                    }
                    itemMatches.forEach { m ->
                        val base = if (sales) m.salePrice else m.costPrice
                        Row(
                            Modifier.fillMaxWidth().background(Color(0xFFF1F5FB)).clickable { addLine(m) }.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(m.name, Modifier.weight(1f), fontSize = 16.sp, maxLines = 1)
                            Text("مخزون ${m.quantity.money()} • ${Ledger.fromBase(base, rate).money()}", fontSize = 13.sp, color = Color.Gray)
                        }
                    }
                }
            }

            // رأس جدول البنود
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("الصنف", Modifier.weight(2.2f), fontSize = 17.sp)
                Text("الكمية", Modifier.weight(1f), fontSize = 17.sp, textAlign = TextAlign.Center)
                Text("السعر", Modifier.weight(1f), fontSize = 17.sp, textAlign = TextAlign.Center)
                Text("الاجمالي", Modifier.weight(1.1f), fontSize = 17.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.width(36.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFBDBDBD)))

            lines.forEachIndexed { idx, l ->
                val lq = l.qty.toMilli()
                val lp = l.price.toMilli()
                val lt = if (lq != null && lp != null && lq > 0 && lp >= 0) Ledger.lineTotal(lq, lp) else null
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(l.item.name, Modifier.weight(2.2f), fontSize = 16.sp, maxLines = 2)
                    NumField(l.qty, { lines[idx] = l.copy(qty = it) }, Modifier.weight(1f).padding(horizontal = 4.dp), isError = lq == null || lq <= 0)
                    NumField(l.price, { lines[idx] = l.copy(price = it) }, Modifier.weight(1f).padding(horizontal = 4.dp), isError = lp == null || lp < 0)
                    Text(lt?.money() ?: "—", Modifier.weight(1.1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
                    IconButton(onClick = { lines.removeAt(idx) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Close, "حذف", tint = RED, modifier = Modifier.size(20.dp))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
            }
            Spacer(Modifier.height(12.dp))
        }

        // الملخص السفلي
        Column(Modifier.fillMaxWidth().background(Color.White)) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(RefBlue))
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                Column(Modifier.weight(1.15f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Underlined("الخصم", Modifier.width(96.dp))
                        NumField(discountText, { discountText = it }, Modifier.width(88.dp), isError = !discOk)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Underlined(if (taxPercent) "الضريبة٪" else "الضريبة", Modifier.width(96.dp))
                        NumField(taxText, { taxText = it }, Modifier.width(88.dp), isError = !taxOk)
                        Spinner("", listOf("نسبة مئوية ٪", "مبلغ ثابت"), { taxPercent = it == 0 })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Underlined("رسوم أخرى", Modifier.width(96.dp))
                        NumField(feesText, { feesText = it }, Modifier.width(88.dp), isError = !feesOk)
                    }
                    if (!cash) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Underlined("المبلغ المدفوع", Modifier.width(96.dp))
                            NumField(paidText, { paidText = it }, Modifier.width(88.dp), isError = !paidOk)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("الصافي", fontSize = 17.sp)
                        Text(net.money(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = RefBlue)
                    }
                    if (rate != 1000L) {
                        Text("≈ ${Ledger.convert(net, rate).money()} محلي", fontSize = 12.sp, color = Color.Gray)
                    }
                    if (!cash) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("المبلغ المتبقي", fontSize = 17.sp)
                            Text(rest.money(), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (rest > 0) ORANGE else Color(0xFF2E7D32))
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        val list = remember(pickerQuery, items) {
            val q = pickerQuery.trim()
            (if (q.isEmpty()) items else items.filter { it.name.contains(q) || it.barcode == q }).take(80)
        }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("اختيار صنف") },
            text = {
                Column {
                    UnderlineField(pickerQuery, { pickerQuery = it }, "بحث")
                    LazyColumn(Modifier.height(320.dp)) {
                        items(list, key = { it.id }) { m ->
                            Row(
                                Modifier.fillMaxWidth().clickable { addLine(m); showPicker = false }.padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(m.name, Modifier.weight(1f), maxLines = 1)
                                Text(m.quantity.money(), color = Color.Gray)
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("إغلاق") } }
        )
    }

    if (showAddCash) {
        var cashNew by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddCash = false },
            title = { Text("إضافة صندوق / بنك") },
            text = { UnderlineField(cashNew, { cashNew = it }, "الاسم (مثل: البنك الأهلي)") },
            confirmButton = {
                TextButton(enabled = cashNew.isNotBlank(), onClick = {
                    scope.launch {
                        val code = withContext(Dispatchers.IO) {
                            val c = ((db.accounts().maxCashCode() ?: 1100) + 1).toString()
                            db.accounts().insert(Account(code = c, name = cashNew.trim(), type = "asset"))
                            c
                        }
                        cashCode = code
                        showAddCash = false
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { showAddCash = false }) { Text("إلغاء") } }
        )
    }
}
