package com.alalamiya.bos

import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ExpensesScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by db.journal().observeExpenses().collectAsStateWithLifecycle(emptyList())
    val cats by db.accounts().observeExpenseCategories().collectAsStateWithLifecycle(emptyList())
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    var show by remember { mutableStateOf(false) }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { show = true }) { Icon(Icons.Filled.Add, "مصروف جديد") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("إجمالي المصروفات المعروضة: ${list.sumOf { it.amount }.money()}", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد مصروفات مسجلة") }
            } else {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { e ->
                        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.description.removePrefix("مصروف: "), style = MaterialTheme.typography.titleSmall)
                                    Text(fmt.format(Date(e.date)), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                Text(e.amount.money(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (show) {
        var category by remember { mutableStateOf("") }
        var amount by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        val a = amount.toMilli()
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("مصروف جديد (من الصندوق)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cats.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            cats.forEach { c -> FilterChip(selected = category == c.name, onClick = { category = c.name }, label = { Text(c.name) }) }
                        }
                    }
                    OutlinedTextField(category, { category = it }, label = { Text("بند المصروف (مثل: إيجار، كهرباء، رواتب)") }, singleLine = true)
                    OutlinedTextField(amount, { amount = it }, label = { Text("المبلغ") }, singleLine = true, isError = amount.isNotBlank() && (a == null || a <= 0))
                    OutlinedTextField(note, { note = it }, label = { Text("ملاحظة") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = category.isNotBlank() && a != null && a > 0, onClick = {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { Ledger(db).postExpense(category, a ?: 0L, note.trim()) }
                            show = false
                        } catch (e: Exception) {
                            Toast.makeText(ctx, e.message ?: "تعذّر الحفظ", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("إلغاء") } }
        )
    }
}
