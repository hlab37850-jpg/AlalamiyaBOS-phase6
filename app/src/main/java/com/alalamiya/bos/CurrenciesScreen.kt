package com.alalamiya.bos

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Currency
import com.alalamiya.bos.data.OperationLog
import com.alalamiya.bos.ui.money
import com.alalamiya.bos.ui.toMilli
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CurrenciesScreen(db: BosDatabase) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list by db.currencies().observeAll().collectAsStateWithLifecycle(emptyList())
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Currency?>(null) }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "عملة جديدة") }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text(
                "العملة الأساسية (YER) تُحفظ بها كل الحسابات. الفواتير بعملة أخرى تُحوَّل بسعر الصرف المسجَّل وقت الحفظ، ولا يتغير أثرها لو تغيّر السعر لاحقًا.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)
            )
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { c ->
                    val base = c.code == "YER"
                    Card(
                        onClick = { if (!base) editing = c }, modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${c.name} (${c.code})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (base) "العملة الأساسية" else "1 ${c.code} = ${c.rate.money()} ${list.firstOrNull { it.code == "YER" }?.name ?: "ريال"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        var code by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var rate by remember { mutableStateOf("") }
        val r = rate.toMilli()
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("عملة جديدة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(code, { code = it.uppercase().take(5) }, label = { Text("الرمز (مثل USD أو SAR)") }, singleLine = true)
                    OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true)
                    OutlinedTextField(rate, { rate = it }, label = { Text("كم ريال تساوي وحدة واحدة؟") }, singleLine = true, isError = rate.isNotBlank() && (r == null || r <= 0))
                }
            },
            confirmButton = {
                TextButton(enabled = code.length >= 2 && name.isNotBlank() && r != null && r > 0, onClick = {
                    scope.launch {
                        val exists = withContext(Dispatchers.IO) { db.currencies().findByCode(code) != null }
                        if (exists) {
                            Toast.makeText(ctx, "الرمز مستخدم مسبقًا", Toast.LENGTH_SHORT).show()
                        } else {
                            withContext(Dispatchers.IO) {
                                db.currencies().insert(Currency(code = code, name = name.trim(), rate = r ?: 1000))
                                db.log().insert(OperationLog(action = "add_currency", details = code))
                            }
                            adding = false
                        }
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("إلغاء") } }
        )
    }

    editing?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.name) }
        var rate by remember(c.id) { mutableStateOf(c.rate.money()) }
        val r = rate.toMilli()
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("تعديل ${c.code}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("الاسم") }, singleLine = true)
                    OutlinedTextField(rate, { rate = it }, label = { Text("سعر الصرف") }, singleLine = true, isError = r == null || r <= 0)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && r != null && r > 0, onClick = {
                    scope.launch(Dispatchers.IO) {
                        db.currencies().update(c.copy(name = name.trim(), rate = r ?: c.rate))
                        db.log().insert(OperationLog(action = "edit_currency", details = c.code))
                    }
                    editing = null
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("إلغاء") } }
        )
    }
}
