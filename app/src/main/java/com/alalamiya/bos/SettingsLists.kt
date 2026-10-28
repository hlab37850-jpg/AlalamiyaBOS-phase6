package com.alalamiya.bos

import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import com.alalamiya.bos.data.AppSetting
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Item
import com.alalamiya.bos.data.ItemUnit
import com.alalamiya.bos.data.Lookup
import com.alalamiya.bos.data.OperationLog
import com.alalamiya.bos.data.Tax
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** حقل نصي مع قائمة اختيار من القائمة المحفوظة (تصنيفات / مجموعات / وحدات قياس). */
@Composable
fun LookupField(db: BosDatabase, kind: Int, label: String, value: String, onChange: (String) -> Unit) {
    val list by db.lookups().observeKind(kind).collectAsStateWithLifecycle(emptyList())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        UnderlineField(value, onChange, label, Modifier.weight(1f))
        if (list.isNotEmpty()) Spinner("اختر", list.map { it.name }, { onChange(list[it].name) })
    }
}

/** kind: 0 تصنيفات، 1 مجموعة الصنف، 2 وحدات القياس. */
@Composable
fun LookupScreen(db: BosDatabase, kind: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by db.lookups().observeKind(kind).collectAsStateWithLifecycle(emptyList())
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var del by remember { mutableStateOf<Lookup?>(null) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UnderlineField(name, { name = it }, "الاسم")
            if (kind == 2) UnderlineField(code, { code = it }, "الرمز المختصر")
            Button(enabled = name.isNotBlank(), onClick = {
                val n = name.trim()
                if (list.any { it.name == n }) { Toast.makeText(ctx, "الاسم موجود مسبقًا", Toast.LENGTH_SHORT).show(); return@Button }
                scope.launch(Dispatchers.IO) { db.lookups().insert(Lookup(kind = kind, name = n, code = code.trim())) }
                name = ""; code = ""
            }) { Text("إضافة") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { l ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(l.name + if (l.code.isNotBlank()) "  (${l.code})" else "", Modifier.weight(1f))
                        TextButton(onClick = { del = l }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    del?.let { d ->
        AlertDialog(
            onDismissRequest = { del = null }, title = { Text("حذف «${d.name}»") },
            text = { Text("يُحذف من القائمة فقط، والأصناف أو الحسابات التي تستخدمه تحتفظ بالاسم المكتوب.") },
            confirmButton = { TextButton(onClick = { scope.launch(Dispatchers.IO) { db.lookups().delete(d) }; del = null }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { del = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun TaxScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val taxes by db.taxes().observeAll().collectAsStateWithLifecycle(emptyList())
    val vatOn by db.settings().observe("vat_on").collectAsStateWithLifecycle(null)
    var name by remember { mutableStateOf("") }
    var pct by remember { mutableStateOf("") }
    val p = pct.toMilli()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("تفعيل ضريبة القيمة المضافة تلقائيًا في الفواتير", Modifier.weight(1f))
            Switch(checked = vatOn == "1", onCheckedChange = { on -> scope.launch(Dispatchers.IO) { db.settings().put(AppSetting("vat_on", if (on) "1" else "0")) } })
        }
        Text("عند التفعيل تُملأ نسبة الضريبة الافتراضية في كل فاتورة جديدة، ويمكن تعديلها أو اختيار ضريبة أخرى من القائمة.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        taxes.forEach { t ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = t.isDefault, onClick = { scope.launch(Dispatchers.IO) { db.taxes().setDefault(t.id) } })
                    Text("${t.name}  —  ${t.percent.money()}%" + if (t.isDefault) "  (الافتراضية)" else "", Modifier.weight(1f))
                    TextButton(onClick = { scope.launch(Dispatchers.IO) { db.taxes().delete(t) } }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Text("إضافة ضريبة", fontWeight = FontWeight.Bold)
        UnderlineField(name, { name = it }, "الاسم")
        UnderlineField(pct, { pct = it }, "النسبة %")
        Button(enabled = name.isNotBlank() && p != null && p > 0 && p <= 100_000L, onClick = {
            scope.launch(Dispatchers.IO) { db.taxes().insert(Tax(name = name.trim(), percent = p ?: 0, isDefault = taxes.isEmpty())) }
            Toast.makeText(ctx, "تمت الإضافة", Toast.LENGTH_SHORT).show()
            name = ""; pct = ""
        }) { Text("إضافة") }
    }
}

@Composable
fun ItemUnitsScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    var q by remember { mutableStateOf("") }
    var item by remember { mutableStateOf<Item?>(null) }
    val units by remember(item?.id) { db.itemUnits().observeFor(item?.id ?: -1L) }.collectAsStateWithLifecycle(emptyList())
    var uname by remember { mutableStateOf("") }
    var factor by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    val matches = remember(q, items) { if (q.isBlank()) emptyList() else items.filter { it.name.contains(q.trim()) }.take(6) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val cur = item
        if (cur == null) {
            UnderlineField(q, { q = it }, "اسم الصنف")
            matches.forEach { m -> TextButton(onClick = { item = m }) { Text(m.name) } }
        } else {
            TextButton(onClick = { item = null; q = "" }) { Text("الصنف: ${cur.name} — تغيير") }
            Text("الوحدة الأساسية: ${cur.unit.ifBlank { "غير محددة" }}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            units.forEach { u ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.padding(10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${u.name} = ${u.factor.money()} ${cur.unit.ifBlank { "وحدة أساسية" }}", fontWeight = FontWeight.Bold)
                            if (u.price > 0) Text("سعر البيع: ${u.price.money()}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { scope.launch(Dispatchers.IO) { db.itemUnits().delete(u) } }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Text("إضافة وحدة فرعية", fontWeight = FontWeight.Bold)
            LookupField(db, 2, "اسم الوحدة (مثل: كرتون)", uname) { uname = it }
            UnderlineField(factor, { factor = it }, "عدد الوحدات الأساسية فيها (مثال 12)")
            UnderlineField(price, { price = it }, "سعر بيع الوحدة (اختياري)")
            val f = factor.toMilli()
            val pr = if (price.isBlank()) 0L else price.toMilli()
            Button(enabled = uname.isNotBlank() && f != null && f > 0 && pr != null && pr >= 0, onClick = {
                scope.launch(Dispatchers.IO) { db.itemUnits().insert(ItemUnit(itemId = cur.id, name = uname.trim(), factor = f ?: 1000, price = pr ?: 0)) }
                Toast.makeText(ctx, "تمت الإضافة", Toast.LENGTH_SHORT).show()
                uname = ""; factor = ""; price = ""
            }) { Text("إضافة") }
        }
    }
}

@Composable
fun PricesScreen(db: BosDatabase) {
    val scope = rememberCoroutineScope()
    val items by db.items().observeAll().collectAsStateWithLifecycle(emptyList())
    var q by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf<Item?>(null) }
    val shown = remember(q, items) { if (q.isBlank()) items else items.filter { it.name.contains(q.trim()) || it.barcode == q.trim() } }
    Column(Modifier.fillMaxSize()) {
        UnderlineField(q, { q = it }, "بحث بالاسم أو الباركود", Modifier.padding(12.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.id }) { i ->
                Card(onClick = { sel = i }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(i.name, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text("التكلفة: ${i.costPrice.money()}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Text(i.salePrice.money(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = RefBlue)
                    }
                }
            }
        }
    }
    sel?.let { i ->
        var p by remember(i.id) { mutableStateOf(i.salePrice.money()) }
        val v = p.toMilli()
        AlertDialog(
            onDismissRequest = { sel = null }, title = { Text(i.name) },
            text = { OutlinedTextField(p, { p = it }, label = { Text("سعر البيع (للوحدة الأساسية ${i.unit})") }, singleLine = true, isError = v == null || v < 0) },
            confirmButton = {
                TextButton(enabled = v != null && v >= 0, onClick = {
                    scope.launch(Dispatchers.IO) {
                        db.items().update(i.copy(salePrice = v ?: 0))
                        db.log().insert(OperationLog(action = "edit_item", details = "${i.name}: سعر البيع"))
                    }
                    sel = null
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { sel = null }) { Text("إلغاء") } }
        )
    }
}
