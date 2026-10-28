package com.alalamiya.bos

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.ui.money

private data class Sheet(val title: String, val amount: Long?, val kind: Int)   // kind: 0 صف، 1 عنوان قسم، 2 إجمالي

/** المركز المالي من دفتر القيود: الأصول = الالتزامات + حقوق الملكية (+ صافي ربح الفترة). */
@Composable
fun BalanceSheetScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val accs by db.accounts().observeAccBalances().collectAsStateWithLifecycle(emptyList())
    val assets = mutableListOf<Pair<String, Long>>()
    val liab = mutableListOf<Pair<String, Long>>()
    val equity = mutableListOf<Pair<String, Long>>()
    var profit = 0L
    for (a in accs) {
        val b = a.debit - a.credit
        when {
            a.type == "income" -> profit -= b
            a.type == "expense" -> profit -= b
            b == 0L -> {}
            a.type == "asset" || (a.type == "other" && b > 0) -> assets.add(a.name to b)
            a.type == "liability" || (a.type == "other" && b < 0) -> liab.add(a.name to -b)
            a.type == "equity" -> equity.add(a.name to -b)
        }
    }
    val tA = assets.sumOf { it.second }
    val tL = liab.sumOf { it.second }
    val tE = equity.sumOf { it.second } + profit
    val rows = mutableListOf<Sheet>()
    rows.add(Sheet("الأصول", null, 1)); assets.forEach { rows.add(Sheet(it.first, it.second, 0)) }; rows.add(Sheet("إجمالي الأصول", tA, 2))
    rows.add(Sheet("الالتزامات", null, 1)); liab.forEach { rows.add(Sheet(it.first, it.second, 0)) }; rows.add(Sheet("إجمالي الالتزامات", tL, 2))
    rows.add(Sheet("حقوق الملكية", null, 1)); equity.forEach { rows.add(Sheet(it.first, it.second, 0)) }
    rows.add(Sheet("صافي ربح / خسارة الفترة", profit, 0)); rows.add(Sheet("إجمالي حقوق الملكية", tE, 2))
    rows.add(Sheet("إجمالي الالتزامات وحقوق الملكية", tL + tE, 2))
    val spec = PdfSpec(
        fileName = "balance-sheet.pdf", title = "المركز المالي", subtitle = Prefs.header(ctx),
        headers = listOf("البند", "المبلغ"), rows = rows.map { listOf(it.title, it.amount?.money() ?: "") },
        footer = listOf(if (tA == tL + tE) "الميزانية متوازنة" else "فرق: ${(tA - tL - tE).money()}"), weights = listOf(2.4f, 1f)
    )
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(rows.size) { i ->
            val r = rows[i]
            when (r.kind) {
                1 -> Text(r.title, Modifier.fillMaxWidth().background(BarBrush).padding(8.dp), color = Color.White, fontWeight = FontWeight.Bold)
                2 -> Row(Modifier.fillMaxWidth().background(Color(0xFFD6E6FA)).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.title, fontWeight = FontWeight.Bold); Text((r.amount ?: 0L).money(), fontWeight = FontWeight.Bold)
                }
                else -> Row(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.title, Modifier.weight(1f)); Text((r.amount ?: 0L).money())
                }
            }
        }
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (tA == tL + tE) "الميزانية متوازنة" else "الميزانية غير متوازنة — الفرق ${(tA - tL - tE).money()}", style = MaterialTheme.typography.bodySmall)
                PdfActions(spec)
            }
        }
    }
}

/** أرصدة العملاء (kind=0) أو الموردين (kind=1). */
@Composable
fun BalancesScreen(db: BosDatabase, kind: Int, onParty: (Long) -> Unit) {
    val ctx = LocalContext.current
    val parties by db.customers().observeKind(kind).collectAsStateWithLifecycle(emptyList())
    val rows = parties.filter { it.balance != 0L }
    val debit = rows.filter { it.balance > 0 }.sumOf { it.balance }
    val credit = rows.filter { it.balance < 0 }.sumOf { -it.balance }
    val title = if (kind == 0) "أرصدة العملاء" else "أرصدة الموردين"
    val spec = PdfSpec(
        fileName = "balances-$kind.pdf", title = title, subtitle = Prefs.header(ctx),
        headers = listOf("الاسم", "الرصيد"), rows = rows.map { listOf(it.name, balText(it.balance)) },
        footer = listOf("إجمالي عليهم: ${debit.money()}", "إجمالي لهم: ${credit.money()}"), weights = listOf(2.5f, 2f)
    )
    Column(Modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("لا توجد أرصدة") }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows, key = { it.id }) { p ->
                    Card(onClick = { onParty(p.id) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(p.name, Modifier.weight(1f), maxLines = 1)
                            Text(balText(p.balance), color = if (p.balance > 0) Color(0xFFB3261E) else Color(0xFF2E7D32), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(BarBrush).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("إجمالي عليهم: ${debit.money()}", color = Color.White, fontWeight = FontWeight.Bold)
            Text("إجمالي لهم: ${credit.money()}", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.padding(12.dp)) { PdfActions(spec) }
    }
}

/** المخزون المتبقي بالكمية والقيمة (بسعر التكلفة)، مع تصفية حسب المستودع. */
@Composable
fun StockRemainingScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val items by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    val warehouses by db.warehouses().observeAll().collectAsStateWithLifecycle(emptyList())
    val stock by db.stock().observeAll().collectAsStateWithLifecycle(emptyList())
    var wid by remember { mutableStateOf<Long?>(null) }   // null = كل المستودعات
    val qtyOf = remember(items, stock, wid) {
        if (wid == null) items.associate { it.id to it.quantity }
        else stock.filter { it.warehouseId == wid }.associate { it.itemId to it.quantity }
    }
    val rows = items.filter { (qtyOf[it.id] ?: 0L) != 0L }
    val total = rows.sumOf { Ledger.lineTotal(qtyOf[it.id] ?: 0L, it.costPrice) }
    val spec = PdfSpec(
        fileName = "stock-remaining.pdf", title = "المخزون المتبقي", subtitle = Prefs.header(ctx),
        headers = listOf("الصنف", "الكمية", "القيمة"),
        rows = rows.map { listOf(it.name, "${(qtyOf[it.id] ?: 0L).money()} ${it.unit}", Ledger.lineTotal(qtyOf[it.id] ?: 0L, it.costPrice).money()) },
        footer = listOf("إجمالي القيمة: ${total.money()}"), weights = listOf(3f, 1.4f, 1.4f)
    )
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = wid == null, onClick = { wid = null }, label = { Text("الكل") })
            warehouses.forEach { w -> FilterChip(selected = wid == w.id, onClick = { wid = w.id }, label = { Text(w.name) }) }
        }
        Row(Modifier.fillMaxWidth().background(BarBrush).padding(10.dp)) {
            Text("الصنف", Modifier.weight(2.4f), color = Color.White, fontWeight = FontWeight.Bold)
            Text("الكمية", Modifier.weight(1.2f), color = Color.White, fontWeight = FontWeight.Bold)
            Text("القيمة", Modifier.weight(1.3f), color = Color.White, fontWeight = FontWeight.Bold)
        }
        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text("لا يوجد مخزون") }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.id }) { i ->
                    val q = qtyOf[i.id] ?: 0L
                    Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(i.name, Modifier.weight(2.4f), maxLines = 1)
                        Text("${q.money()} ${i.unit}", Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                        Text(Ledger.lineTotal(q, i.costPrice).money(), Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall)
                    }
                    Box(Modifier.fillMaxWidth().padding(0.dp).background(Color(0xFFEEEEEE)).padding(vertical = 0.5.dp))
                }
            }
        }
        Text("إجمالي القيمة (بسعر التكلفة): ${total.money()}", Modifier.fillMaxWidth().background(BarBrush).padding(14.dp), color = Color.White, fontWeight = FontWeight.Bold)
        Box(Modifier.padding(12.dp)) { PdfActions(spec) }
    }
}
