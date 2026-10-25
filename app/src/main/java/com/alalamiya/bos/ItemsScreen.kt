package com.alalamiya.bos

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Item
import com.alalamiya.bos.data.OperationLog
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ItemDialog(initial: Item?, onDismiss: () -> Unit, onSave: (Item) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var unit by remember { mutableStateOf(initial?.unit ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: "") }
    var barcode by remember { mutableStateOf(initial?.barcode ?: "") }
    var cost by remember { mutableStateOf(initial?.costPrice?.money() ?: "") }
    var sale by remember { mutableStateOf(initial?.salePrice?.money() ?: "") }
    var min by remember { mutableStateOf(initial?.minStock?.money() ?: "") }
    var qty by remember { mutableStateOf("") }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { barcode = it } }
    val c = cost.toMilli()
    val s = sale.toMilli()
    val m = min.toMilli()
    val q = qty.toMilli()
    val valid = name.isNotBlank() && c != null && s != null && m != null && (initial != null || q != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "صنف جديد" else "تعديل الصنف") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("اسم الصنف") })
                OutlinedTextField(unit, { unit = it }, label = { Text("الوحدة") }, singleLine = true)
                OutlinedTextField(category, { category = it }, label = { Text("التصنيف") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(barcode, { barcode = it }, label = { Text("الباركود") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scan.launch(ScanOptions().setPrompt("امسح الباركود")) }) { Text("مسح") }
                }
                OutlinedTextField(cost, { cost = it }, label = { Text("سعر التكلفة") }, singleLine = true, isError = c == null)
                OutlinedTextField(sale, { sale = it }, label = { Text("سعر البيع") }, singleLine = true, isError = s == null)
                OutlinedTextField(min, { min = it }, label = { Text("حد الطلب (الكمية الدنيا)") }, singleLine = true, isError = m == null)
                if (initial == null) {
                    OutlinedTextField(qty, { qty = it }, label = { Text("الكمية الافتتاحية") }, singleLine = true, isError = q == null)
                } else {
                    Text("الكمية الحالية: ${initial.quantity.money()} (تتغير بالفواتير فقط)", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val item = initial?.copy(
                    name = name.trim(), unit = unit.trim(), category = category.trim(), barcode = barcode.trim(),
                    costPrice = c ?: 0, salePrice = s ?: 0, minStock = m ?: 0
                ) ?: Item(
                    name = name.trim(), unit = unit.trim(), category = category.trim(), barcode = barcode.trim(),
                    costPrice = c ?: 0, salePrice = s ?: 0, minStock = m ?: 0, quantity = q ?: 0
                )
                onSave(item)
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
fun ItemsScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Item?>(null) }
    val shown = remember(list, query) {
        val q = query.trim()
        if (q.isEmpty()) list else list.filter { it.name.contains(q) || it.barcode.contains(q) || it.category.contains(q) }
    }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, "إضافة") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, label = { Text("بحث بالاسم أو الباركود") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { i ->
                    val low = i.minStock > 0 && i.quantity <= i.minStock
                    Card(onClick = { editing = i }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(i.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    listOf(i.unit, if (i.salePrice > 0) "سعر البيع ${i.salePrice.money()}" else "").filter { it.isNotBlank() }.joinToString(" — "),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                i.quantity.money(), style = MaterialTheme.typography.titleMedium,
                                color = if (low) Color(0xFFB3261E) else Color.Unspecified
                            )
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        ItemDialog(null, { creating = false }) { item ->
            scope.launch {
                val dup = withContext(Dispatchers.IO) { db.items().findByName(item.name) != null }
                if (dup) {
                    Toast.makeText(ctx, "يوجد صنف بنفس الاسم", Toast.LENGTH_SHORT).show()
                } else {
                    withContext(Dispatchers.IO) {
                        db.items().insert(item)
                        db.log().insert(OperationLog(action = "add_item", details = item.name))
                    }
                    creating = false
                }
            }
        }
    }
    editing?.let { cur ->
        ItemDialog(cur, { editing = null }) { item ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    db.items().update(item)
                    db.log().insert(OperationLog(action = "edit_item", details = item.name))
                }
                editing = null
            }
        }
    }
}
