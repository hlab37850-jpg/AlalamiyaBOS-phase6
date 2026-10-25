package com.alalamiya.bos

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Item
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun StockCountScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    val warehouses by db.warehouses().observeAll().collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Item?>(null) }
    var counted by remember { mutableStateOf("") }
    var wid by remember { mutableStateOf<Long?>(null) }
    val effectiveWh = wid ?: warehouses.firstOrNull { it.isDefault }?.id ?: 1L
    val matches = remember(query, items) {
        if (query.isBlank()) emptyList() else items.filter { it.name.contains(query.trim()) || it.barcode == query.trim() }.take(6)
    }
    val cur = selected?.let { s -> items.firstOrNull { it.id == s.id } ?: s }
    val stockRows by remember(cur?.id) { db.stock().observeForItem(cur?.id ?: -1L) }.collectAsStateWithLifecycle(emptyList())
    val systemQty = stockRows.firstOrNull { it.warehouseId == effectiveWh }?.quantity ?: 0L

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("تسوية الجرد تضبط كمية الصنف في المستودع على الكمية الفعلية، وتسجّل فرق الكمية بسعر التكلفة في حساب «فروقات الجرد».", style = MaterialTheme.typography.bodySmall)
        if (warehouses.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                warehouses.forEach { w -> FilterChip(selected = effectiveWh == w.id, onClick = { wid = w.id }, label = { Text(w.name) }) }
            }
        }
        if (cur == null) {
            OutlinedTextField(query, { query = it }, label = { Text("ابحث عن صنف أو أدخل الباركود") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            matches.forEach { m -> TextButton(onClick = { selected = m }) { Text("${m.name}  (${m.quantity.money()})") } }
        } else {
            Text(cur.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("الكمية في النظام لهذا المستودع: ${systemQty.money()} ${cur.unit}   |   التكلفة: ${cur.costPrice.money()}")
            OutlinedTextField(counted, { counted = it }, label = { Text("الكمية الفعلية بعد الجرد") }, singleLine = true, modifier = Modifier.fillMaxWidth(), isError = counted.isNotBlank() && counted.toMilli() == null)
            val c = counted.toMilli()
            if (counted.isNotBlank() && c != null && c >= 0) {
                val diff = c - systemQty
                Text(
                    when {
                        diff == 0L -> "لا يوجد فرق"
                        diff < 0 -> "عجز ${(-diff).money()} — قيمته ${Ledger.lineTotal(abs(diff), cur.costPrice).money()}"
                        else -> "زيادة ${diff.money()} — قيمتها ${Ledger.lineTotal(abs(diff), cur.costPrice).money()}"
                    },
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Button(enabled = counted.isNotBlank() && c != null && c >= 0, onClick = {
                val target = c ?: 0L
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { Ledger(db).adjustStock(cur.id, effectiveWh, target) }
                        Toast.makeText(ctx, "تم اعتماد الجرد", Toast.LENGTH_SHORT).show()
                        selected = null; counted = ""; query = ""
                    } catch (e: Exception) {
                        Toast.makeText(ctx, e.message ?: "تعذّر الاعتماد", Toast.LENGTH_SHORT).show()
                    }
                }
            }) { Text("اعتماد الجرد") }
            TextButton(onClick = { selected = null; counted = "" }) { Text("تغيير الصنف") }
        }
    }
}
