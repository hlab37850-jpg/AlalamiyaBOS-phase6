package com.alalamiya.bos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Customer
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VouchersScreen(db: BosDatabase) {
    val list by db.vouchers().observeRecent().collectAsStateWithLifecycle(emptyList())
    val customers by db.customers().observeAll().collectAsStateWithLifecycle(emptyList())
    val names = remember(customers) { customers.associate { it.id to it.name } }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    val ledger = remember { Ledger(db) }
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { show = true }) { Icon(Icons.Filled.Add, "سند جديد") }
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(list, key = { it.id }) { v ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text((if (v.type == "receipt") "سند قبض" else "سند صرف") + " — " + (names[v.customerId] ?: "—"), style = MaterialTheme.typography.titleSmall)
                            Text(fmt.format(Date(v.date)), style = MaterialTheme.typography.bodySmall)
                            if (v.note.isNotBlank()) Text(v.note, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(v.amount.money(), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    if (show) {
        var type by remember { mutableStateOf("receipt") }
        var query by remember { mutableStateOf("") }
        var chosen by remember { mutableStateOf<Customer?>(null) }
        var amount by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val amt = amount.toMilli()
        val matches = remember(query, customers) {
            if (query.isBlank()) emptyList() else customers.filter { it.name.contains(query.trim()) }.take(5)
        }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("سند جديد") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = type == "receipt", onClick = { type = "receipt" }, label = { Text("قبض") })
                        FilterChip(selected = type == "payment", onClick = { type = "payment" }, label = { Text("صرف") })
                    }
                    if (chosen == null) {
                        OutlinedTextField(query, { query = it }, label = { Text("ابحث عن العميل") }, singleLine = true)
                        matches.forEach { c ->
                            TextButton(onClick = { chosen = c }) { Text("${c.name}  (${c.balance.money()})") }
                        }
                    } else {
                        TextButton(onClick = { chosen = null }) { Text("العميل: ${chosen!!.name} — تغيير") }
                    }
                    OutlinedTextField(amount, { amount = it }, label = { Text("المبلغ") }, singleLine = true, isError = amt == null || amt <= 0)
                    OutlinedTextField(note, { note = it }, label = { Text("ملاحظة") }, singleLine = true)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = chosen != null && amt != null && amt > 0, onClick = {
                    val c = chosen!!
                    val a = amt!!
                    scope.launch(Dispatchers.IO) {
                        try {
                            ledger.postVoucher(type, c.id, a, note.trim())
                        } catch (e: Exception) {
                            // لا يُحفظ شيء عند الفشل لأن العملية داخل معاملة واحدة
                        }
                    }
                    show = false
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("إلغاء") } }
        )
    }
}
