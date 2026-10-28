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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Customer
import com.alalamiya.bos.data.OperationLog
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CustomerDialog(initial: Customer?, onDismiss: () -> Unit, onSave: (Customer) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: 0) }
    var vat by remember { mutableStateOf(initial?.vatNo ?: "") }
    var group by remember { mutableStateOf(initial?.groupName ?: "") }
    var bal by remember { mutableStateOf("") }
    val balValue = bal.toMilli()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "عميل جديد" else "تعديل بيانات العميل") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("الهاتف") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("العنوان") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("ملاحظات") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == 0, onClick = { kind = 0 }, label = { Text("عميل") })
                    FilterChip(selected = kind == 1, onClick = { kind = 1 }, label = { Text("مورد") })
                }
                OutlinedTextField(vat, { vat = it }, label = { Text("الرقم الضريبي") }, singleLine = true)
                OutlinedTextField(group, { group = it }, label = { Text("التصنيف") }, singleLine = true)
                if (initial == null) {
                    OutlinedTextField(bal, { bal = it }, label = { Text("الرصيد الافتتاحي (موجب = عليه)") }, singleLine = true, isError = balValue == null)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && (initial != null || balValue != null), onClick = {
                val c = initial?.copy(name = name.trim(), phone = phone.trim(), address = address.trim(), notes = notes.trim(), kind = kind, vatNo = vat.trim(), groupName = group.trim())
                    ?: Customer(name = name.trim(), phone = phone.trim(), address = address.trim(), notes = notes.trim(), balance = balValue ?: 0, kind = kind, vatNo = vat.trim(), groupName = group.trim())
                onSave(c)
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
fun CustomersScreen(db: BosDatabase, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by db.customers().observeAll().collectAsStateWithLifecycle(emptyList())
    var query by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val shown = remember(list, query) {
        val q = query.trim()
        if (q.isEmpty()) list else list.filter { it.name.contains(q) || it.phone.contains(q) }
    }
    val now = System.currentTimeMillis()

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { show = true }) { Icon(Icons.Filled.Add, "إضافة") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, label = { Text("بحث بالاسم أو الهاتف") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { c ->
                    Card(onClick = { onOpen(c.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.titleMedium)
                                if (c.phone.isNotBlank()) Text(c.phone, style = MaterialTheme.typography.bodySmall)
                                val due = c.dueDate
                                if (due != null && c.balance > 0 && due < now) {
                                    Text("متأخر السداد", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Text(c.balance.money(), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }

    if (show) {
        CustomerDialog(null, { show = false }) { c ->
            scope.launch {
                val dup = withContext(Dispatchers.IO) { db.customers().findByName(c.name) != null }
                if (dup) {
                    Toast.makeText(ctx, "يوجد عميل بنفس الاسم", Toast.LENGTH_SHORT).show()
                } else {
                    withContext(Dispatchers.IO) {
                        db.customers().insert(c)
                        db.log().insert(OperationLog(action = "add_customer", details = c.name))
                    }
                    show = false
                }
            }
        }
    }
}
