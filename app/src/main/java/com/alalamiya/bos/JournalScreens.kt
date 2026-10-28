package com.alalamiya.bos

import android.widget.Toast
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.alalamiya.bos.data.ExpenseRow
import com.alalamiya.bos.data.JournalLine
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** قائمة القيود اليومية (opening=false) أو الافتتاحية (opening=true) مع إضافة وحذف. */
@Composable
fun EntryListScreen(db: BosDatabase, opening: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by db.journal().observeByType(if (opening) "opening" else "journal").collectAsStateWithLifecycle(emptyList())
    val accounts by db.accounts().observeAllAccounts().collectAsStateWithLifecycle(emptyList())
    val accNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ExpenseRow?>(null) }

    Scaffold(floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "قيد جديد") } }) { pad ->
        if (list.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد قيود") }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { e ->
                    Card(onClick = { selected = e }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.description, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(fmt.format(Date(e.date)), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Text(e.amount.money(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    selected?.let { e ->
        var lines by remember(e.id) { mutableStateOf<List<JournalLine>>(emptyList()) }
        var confirm by remember(e.id) { mutableStateOf(false) }
        LaunchedEffect(e.id) { lines = withContext(Dispatchers.IO) { db.journal().linesFor(e.id) } }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(if (opening) "قيد إفتتاحي #${e.id}" else "قيد يومي #${e.id}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(fmt.format(Date(e.date)) + "  •  " + e.description, style = MaterialTheme.typography.bodySmall)
                    lines.forEach { l ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(accNames[l.accountId] ?: "حساب #${l.accountId}", Modifier.weight(1f))
                            Text(if (l.debit > 0) "مدين ${l.debit.money()}" else "دائن ${l.credit.money()}", color = if (l.debit > 0) Color(0xFFB3261E) else Color(0xFF2E7D32))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirm = true }) { Text("حذف القيد", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("إغلاق") } }
        )
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false }, title = { Text("حذف القيد") },
                text = { Text("سيُحذف القيد وتُعاد أرصدة الأطراف كما كانت قبله.") },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch {
                            try { withContext(Dispatchers.IO) { Ledger(db).deleteManualEntry(e.id) }; confirm = false; selected = null }
                            catch (ex: Exception) { Toast.makeText(ctx, ex.message ?: "تعذّر الحذف", Toast.LENGTH_SHORT).show(); confirm = false }
                        }
                    }) { Text("حذف") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء") } }
            )
        }
    }

    if (adding) {
        var debit by remember { mutableStateOf<Pair<AccRef, String>?>(null) }
        var credit by remember { mutableStateOf<Pair<AccRef, String>?>(null) }
        var amount by remember { mutableStateOf("") }
        var isDebit by remember { mutableStateOf(true) }
        var date by remember { mutableStateOf(System.currentTimeMillis()) }
        var remarks by remember { mutableStateOf("") }
        var pick by remember { mutableStateOf(0) }   // 1 مدين، 2 دائن
        val a = amount.toMilli()
        val ok = a != null && a > 0 && if (opening) debit != null else (debit != null && credit != null)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(if (opening) "قيد إفتتاحي جديد" else "قيد يومي جديد") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (opening) {
                        OutlinedButton(onClick = { pick = 1 }, modifier = Modifier.fillMaxWidth()) { Text(debit?.second ?: "اختيار الحساب") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = isDebit, onClick = { isDebit = true }, label = { Text("عليه (مدين)") })
                            FilterChip(selected = !isDebit, onClick = { isDebit = false }, label = { Text("له (دائن)") })
                        }
                    } else {
                        Text("من حساب (مدين)", style = MaterialTheme.typography.labelMedium)
                        OutlinedButton(onClick = { pick = 1 }, modifier = Modifier.fillMaxWidth()) { Text(debit?.second ?: "اختيار الحساب") }
                        Text("إلى حساب (دائن)", style = MaterialTheme.typography.labelMedium)
                        OutlinedButton(onClick = { pick = 2 }, modifier = Modifier.fillMaxWidth()) { Text(credit?.second ?: "اختيار الحساب") }
                    }
                    UnderlineField(amount, { amount = it }, "المبلغ")
                    OutlinedButton(onClick = { pickDate(ctx, date, false) { date = it } }, modifier = Modifier.fillMaxWidth()) { Text("التاريخ: " + fmt.format(Date(date))) }
                    if (!opening) UnderlineField(remarks, { remarks = it }, "البيان")
                }
            },
            confirmButton = {
                TextButton(enabled = ok, onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val l = Ledger(db)
                                if (opening) l.postOpening(debit!!.first, a ?: 0L, isDebit, date)
                                else l.postJournal(debit!!.first, credit!!.first, a ?: 0L, date, remarks.trim())
                            }
                            adding = false
                        } catch (ex: Exception) {
                            Toast.makeText(ctx, ex.message ?: "تعذّر الحفظ", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("إلغاء") } }
        )
        if (pick != 0) {
            AccountPickerDialog(db, onDismiss = { pick = 0 }) { ref, name ->
                if (pick == 1) debit = ref to name else credit = ref to name
                pick = 0
            }
        }
    }
}
