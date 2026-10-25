package com.alalamiya.bos

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Item
import com.alalamiya.bos.data.OperationLog
import com.alalamiya.bos.data.Warehouse
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WarehousesScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val warehouses by db.warehouses().observeAll().collectAsStateWithLifecycle(emptyList())
    val items by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Warehouse?>(null) }
    var query by remember { mutableStateOf("") }
    var item by remember { mutableStateOf<Item?>(null) }
    var from by remember { mutableStateOf<Long?>(null) }
    var to by remember { mutableStateOf<Long?>(null) }
    var qty by remember { mutableStateOf("") }
    val stockRows by remember(item?.id) { db.stock().observeForItem(item?.id ?: -1L) }.collectAsStateWithLifecycle(emptyList())
    val matches = remember(query, items) {
        if (query.isBlank()) emptyList() else items.filter { it.name.contains(query.trim()) }.take(6)
    }
    val white = CardDefaults.cardColors(containerColor = Color.White)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), colors = white) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("المستودعات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                warehouses.forEach { w ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(w.name + if (w.isDefault) "  (الافتراضي)" else "")
                        TextButton(onClick = { renaming = w }) { Text("تعديل الاسم") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("اسم مستودع جديد") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(enabled = newName.isNotBlank(), onClick = {
                        scope.launch(Dispatchers.IO) { db.warehouses().insert(Warehouse(name = newName.trim())) }
                        newName = ""
                    }) { Text("إضافة") }
                }
            }
        }

        Card(Modifier.fillMaxWidth(), colors = white) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("مخزون صنف حسب المستودع والمناقلة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val cur = item
                if (cur == null) {
                    OutlinedTextField(query, { query = it }, label = { Text("ابحث عن صنف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    matches.forEach { m -> TextButton(onClick = { item = m }) { Text(m.name) } }
                } else {
                    TextButton(onClick = { item = null; query = ""; qty = "" }) { Text("الصنف: ${cur.name} — تغيير") }
                    warehouses.forEach { w ->
                        val q = stockRows.firstOrNull { it.warehouseId == w.id }?.quantity ?: 0L
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(w.name); Text("${q.money()} ${cur.unit}", fontWeight = FontWeight.Bold)
                        }
                    }
                    Text("الإجمالي: ${cur.quantity.money()} ${cur.unit}", style = MaterialTheme.typography.titleSmall)
                    if (warehouses.size > 1) {
                        Text("مناقلة من:", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            warehouses.forEach { w -> FilterChip(selected = from == w.id, onClick = { from = w.id }, label = { Text(w.name) }) }
                        }
                        Text("إلى:", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            warehouses.forEach { w -> FilterChip(selected = to == w.id, onClick = { to = w.id }, label = { Text(w.name) }) }
                        }
                        val q = qty.toMilli()
                        OutlinedTextField(qty, { qty = it }, label = { Text("الكمية") }, singleLine = true, isError = qty.isNotBlank() && (q == null || q <= 0), modifier = Modifier.fillMaxWidth())
                        Button(enabled = from != null && to != null && q != null && q > 0, onClick = {
                            val f = from ?: return@Button
                            val t = to ?: return@Button
                            val amount = q ?: return@Button
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { Ledger(db).transferStock(cur.id, f, t, amount) }
                                    Toast.makeText(ctx, "تمت المناقلة", Toast.LENGTH_SHORT).show()
                                    qty = ""
                                } catch (e: Exception) {
                                    Toast.makeText(ctx, e.message ?: "تعذّرت المناقلة", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) { Text("تنفيذ المناقلة") }
                    } else {
                        Text("أضف مستودعًا ثانيًا لتفعيل المناقلة.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    renaming?.let { w ->
        var text by remember(w.id) { mutableStateOf(w.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("تعديل اسم المستودع") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    scope.launch(Dispatchers.IO) {
                        db.warehouses().update(w.copy(name = text.trim()))
                        db.log().insert(OperationLog(action = "edit_warehouse", details = text.trim()))
                    }
                    renaming = null
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("إلغاء") } }
        )
    }
}
