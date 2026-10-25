package com.alalamiya.bos

import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.ui.money
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TrialBalanceScreen(db: BosDatabase) {
    val rows by db.accounts().observeBalances().collectAsStateWithLifecycle(emptyList())
    val td = rows.sumOf { it.debit }
    val tc = rows.sumOf { it.credit }
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد قيود محاسبية بعد") }
    } else {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().background(BosBlue).padding(10.dp)) {
                Text("الحساب", Modifier.weight(2f), color = Color.White, fontWeight = FontWeight.Bold)
                Text("مدين", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
                Text("دائن", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(rows, key = { it.code }) { r ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.code}  ${r.name}", Modifier.weight(2f))
                            Text(r.debit.money(), Modifier.weight(1f))
                            Text(r.credit.money(), Modifier.weight(1f))
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFD6E6FA))) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("الإجمالي", Modifier.weight(2f), fontWeight = FontWeight.Bold)
                        Text(td.money(), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text(tc.money(), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    }
                    Text(
                        if (td == tc) "الميزان متوازن" else "الميزان غير متوازن — الفرق ${(td - tc).money()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            val ctx = LocalContext.current
            PdfActions(
                PdfSpec(
                    fileName = "trial-balance.pdf",
                    title = "ميزان المراجعة",
                    subtitle = Prefs.header(ctx),
                    headers = listOf("الحساب", "مدين", "دائن"),
                    rows = rows.map { listOf("${it.code}  ${it.name}", it.debit.money(), it.credit.money()) },
                    footer = listOf("إجمالي المدين: ${td.money()}", "إجمالي الدائن: ${tc.money()}"),
                    weights = listOf(2f, 1f, 1f)
                )
            )
        }
    }
}

private fun actionLabel(a: String): String = when {
    a == "add_customer" -> "إضافة عميل"
    a == "add_item" -> "إضافة صنف"
    a == "voucher_receipt" -> "سند قبض"
    a == "voucher_payment" -> "سند صرف"
    a == "invoice_sale" -> "فاتورة بيع"
    a == "invoice_purchase" -> "فاتورة شراء"
    a == "invoice_cancel" -> "إلغاء فاتورة"
    a == "stock_count" -> "جرد مخزون"
    a == "stock_transfer" -> "مناقلة مخزون"
    a == "expense" -> "مصروف"
    a == "edit_customer" -> "تعديل عميل"
    a == "edit_item" -> "تعديل صنف"
    a == "invoice_sale_return" -> "مرتجع بيع"
    a == "invoice_purchase_return" -> "مرتجع شراء"
    else -> a
}

@Composable
fun LogScreen(db: BosDatabase) {
    val list by db.log().observeRecent().collectAsStateWithLifecycle(emptyList())
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد عمليات مسجلة بعد") }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { l ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(actionLabel(l.action), fontWeight = FontWeight.Bold)
                        if (l.details.isNotBlank()) Text(l.details, style = MaterialTheme.typography.bodySmall)
                        Text(fmt.format(Date(l.ts)), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
