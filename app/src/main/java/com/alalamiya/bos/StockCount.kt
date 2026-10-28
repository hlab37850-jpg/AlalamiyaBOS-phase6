package com.alalamiya.bos

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** جرد مخزني: أدخل الكمية الفعلية للأصناف التي جرّدتها فقط، ويُسجَّل الفرق كتسوية بقيد محاسبي. */
@Composable
fun StockCountScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    val warehouses by db.warehouses().observeAll().collectAsStateWithLifecycle(emptyList())
    var wid by remember { mutableStateOf<Long?>(null) }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val counted = remember { mutableStateMapOf<Long, String>() }
    val effWh = wid ?: warehouses.firstOrNull { it.isDefault }?.id ?: 1L
    val whName = warehouses.firstOrNull { it.id == effWh }?.name ?: "المستودع الرئيسي"
    val stock by remember(effWh) { db.stock().observeForWarehouse(effWh) }.collectAsStateWithLifecycle(emptyList())
    val sysQty = remember(stock) { stock.associate { it.itemId to it.quantity } }
    val shown = remember(q, items) { if (q.isBlank()) items else items.filter { it.name.contains(q.trim()) || it.barcode == q.trim() } }

    // الفروق المدخلة
    val diffs = items.mapNotNull { i ->
        val t = counted[i.id]
        if (t.isNullOrBlank()) null else {
            val c = t.toMilli()
            if (c == null || c < 0) null else MoveLineIn(i.id, c - (sysQty[i.id] ?: 0L))
        }
    }.filter { it.quantity != 0L }
    val invalid = counted.values.any { it.isNotBlank() && ((it.toMilli() ?: -1L) < 0) }

    Column(Modifier.fillMaxSize().background(RefBg)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("المستودع:")
            Spinner(whName, warehouses.map { it.name }, { wid = warehouses[it].id })
        }
        UnderlineField(q, { q = it }, "بحث بالاسم أو الباركود", Modifier.padding(horizontal = 12.dp))
        Text("أدخل الكمية الفعلية فقط للأصناف التي جرّدتها، وسيُسجَّل الفرق كتسوية.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(shown, key = { it.id }) { i ->
                Row(Modifier.fillMaxWidth().background(Color.White).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.6f)) {
                        Text(i.name, maxLines = 1, fontWeight = FontWeight.Medium)
                        Text("الدفتري: ${(sysQty[i.id] ?: 0L).money()} ${i.unit}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    NumField(counted[i.id] ?: "", { counted[i.id] = it }, Modifier.weight(1f), isError = (counted[i.id] ?: "").let { it.isNotBlank() && ((it.toMilli() ?: -1L) < 0) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("أصناف بها فرق: ${diffs.size}", style = MaterialTheme.typography.titleSmall)
            Button(enabled = diffs.isNotEmpty() && !invalid && !busy, onClick = {
                scope.launch {
                    busy = true
                    try {
                        withContext(Dispatchers.IO) { Ledger(db).postStockMove("adjust", effWh, null, diffs, "جرد مخزني") }
                        Toast.makeText(ctx, "تم تسجيل فروقات الجرد", Toast.LENGTH_SHORT).show()
                        counted.clear()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, e.message ?: "تعذّر الحفظ", Toast.LENGTH_LONG).show()
                    }
                    busy = false
                }
            }) { Text("اعتماد الجرد") }
        }
    }
}
