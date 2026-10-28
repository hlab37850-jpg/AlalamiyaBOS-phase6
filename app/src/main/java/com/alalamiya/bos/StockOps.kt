package com.alalamiya.bos

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.alalamiya.bos.data.StockMove
import com.alalamiya.bos.data.StockMoveLine
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun moveTitle(t: String) = when (t) {
    "transfer" -> "تحويل مخزني"; "receipt" -> "توريد مخزني"; "issue" -> "صرف مخزني"; else -> "تسوية مخزنية"
}

private data class MDraft(val item: Item, val qty: String, val cost: String)

/** عملية مخزنية متعددة الأصناف: transfer / receipt / issue / adjust. */
@Composable
fun StockMoveScreen(db: BosDatabase, type: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    val warehouses by db.warehouses().observeAll().collectAsStateWithLifecycle(emptyList())
    var wid by remember { mutableStateOf<Long?>(null) }
    var toWid by remember { mutableStateOf<Long?>(null) }
    var remarks by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(System.currentTimeMillis()) }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val lines = remember { mutableStateListOf<MDraft>() }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val effWh = wid ?: warehouses.firstOrNull { it.isDefault }?.id ?: 1L
    val whName = warehouses.firstOrNull { it.id == effWh }?.name ?: "المستودع الرئيسي"
    val toName = warehouses.firstOrNull { it.id == toWid }?.name ?: "اختر المستودع"
    val matches = remember(q, items) { if (q.isBlank()) emptyList() else items.filter { it.name.contains(q.trim()) || it.barcode == q.trim() }.take(6) }
    val needCost = type == "receipt"

    val parsed = lines.map { l ->
        val qv = l.qty.toMilli()
        val cv = if (l.cost.isBlank()) 0L else l.cost.toMilli()
        if (qv == null || cv == null || cv < 0 || qv == 0L || (type != "adjust" && qv < 0)) null else MoveLineIn(l.item.id, qv, cv)
    }
    val valid = lines.isNotEmpty() && parsed.all { it != null } && (type != "transfer" || (toWid != null && toWid != effWh))

    Column(Modifier.fillMaxSize().background(RefBg)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RefCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (type == "transfer") "من:" else "المستودع:")
                        Spinner(whName, warehouses.map { it.name }, { wid = warehouses[it].id })
                        if (type == "transfer") {
                            Text("إلى:")
                            Spinner(toName, warehouses.map { it.name }, { toWid = warehouses[it].id })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt.format(Date(date)), fontWeight = FontWeight.Bold)
                        TextButton(onClick = { pickDate(ctx, date, false) { date = it } }) { Text("تغيير التاريخ") }
                    }
                    UnderlineField(remarks, { remarks = it }, "ملاحظات")
                }
            }
            if (type == "adjust") Text("للتسوية: اكتب الكمية موجبة للزيادة وسالبة (مثل -3) للعجز أو التالف.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            RefCard {
                Column {
                    UnderlineField(q, { q = it }, "إكتب اسم الصنف أو الباركود", centerHint = true)
                    matches.forEach { m ->
                        Row(Modifier.fillMaxWidth().background(Color(0xFFF1F5FB)).padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { lines.add(MDraft(m, "1", if (needCost && m.costPrice > 0) m.costPrice.money() else "")); q = "" }, modifier = Modifier.weight(1f)) { Text(m.name, maxLines = 1) }
                            Text("مخزون ${m.quantity.money()}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            lines.forEachIndexed { i, l ->
                val qv = l.qty.toMilli()
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(l.item.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { lines.removeAt(i) }) { Icon(Icons.Filled.Close, "حذف", tint = Color(0xFFB3261E)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("الكمية (${l.item.unit})", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                NumField(l.qty, { lines[i] = l.copy(qty = it) }, isError = qv == null || qv == 0L || (type != "adjust" && qv < 0))
                            }
                            if (needCost) Column(Modifier.weight(1f)) {
                                Text("تكلفة الوحدة", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                NumField(l.cost, { lines[i] = l.copy(cost = it) })
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = valid && !busy, modifier = Modifier.weight(1f), onClick = {
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { Ledger(db).postStockMove(type, effWh, toWid, parsed.filterNotNull(), remarks.trim(), date) }
                        Toast.makeText(ctx, "تم الحفظ", Toast.LENGTH_SHORT).show()
                        onDone()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, e.message ?: "تعذّر الحفظ", Toast.LENGTH_LONG).show()
                    }
                    busy = false
                }
            }) { Text("حفظ ${moveTitle(type)}") }
            TextButton(onClick = onDone) { Text("إلغاء") }
        }
    }
}

/** قائمة العمليات المخزنية (تحويل، توريد، صرف، تسوية). */
@Composable
fun StockOpsListScreen(db: BosDatabase) {
    val moves by db.stockMoves().observeRecent().collectAsStateWithLifecycle(emptyList())
    val warehouses by db.warehouses().observeAll().collectAsStateWithLifecycle(emptyList())
    val items by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    val wNames = remember(warehouses) { warehouses.associate { it.id to it.name } }
    val iNames = remember(items) { items.associate { it.id to it.name } }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    var sel by remember { mutableStateOf<StockMove?>(null) }
    if (moves.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد عمليات مخزنية") }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(moves, key = { it.id }) { m ->
                Card(onClick = { sel = m }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${moveTitle(m.type)} #${m.id}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            fmt.format(Date(m.date)) + "  •  " + (wNames[m.warehouseId] ?: "") + (m.toWarehouseId?.let { " ← " + (wNames[it] ?: "") } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray
                        )
                    }
                }
            }
        }
    }
    sel?.let { m ->
        var lines by remember(m.id) { mutableStateOf<List<StockMoveLine>>(emptyList()) }
        LaunchedEffect(m.id) { lines = withContext(Dispatchers.IO) { db.stockMoves().linesOf(m.id) } }
        AlertDialog(
            onDismissRequest = { sel = null },
            title = { Text("${moveTitle(m.type)} #${m.id}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (m.remarks.isNotBlank()) Text(m.remarks, style = MaterialTheme.typography.bodySmall)
                    lines.forEach { l ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(iNames[l.itemId] ?: "#${l.itemId}", Modifier.weight(1f))
                            Text((if (m.type == "adjust" && l.quantity > 0) "+" else "") + l.quantity.money(), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { sel = null }) { Text("إغلاق") } }
        )
    }
}
