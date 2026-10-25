package com.alalamiya.bos

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.ui.money

val BosBlue = Color(0xFF1976D2)
val BosBlueDark = Color(0xFF0D47A1)
val BosGold = Color(0xFFFFB300)
private val BosBg = Color(0xFFF1F5FB)
private val BosTint = Color(0xFFE3F0FF)

@Composable
fun BosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = BosBlue,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD6E6FA),
            secondary = BosGold,
            onSecondary = Color.Black,
            background = BosBg,
            surface = Color.White,
            surfaceContainerHighest = Color.White
        ),
        content = content
    )
}

val BarBrush = Brush.horizontalGradient(listOf(Color(0xFF1B4F8F), Color(0xFF0A6FB0), Color(0xFF174D8B)))

@Composable
fun GradientBar(title: String, onBack: (() -> Unit)?) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxWidth().background(BarBrush)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = Color.White) }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    title, Modifier.weight(1f).padding(horizontal = 8.dp), color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Left, maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun RowScope.Tile(label: String, icon: ImageVector, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(112.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(BosTint), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = BosBlue, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MenuSection(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = BosBlue)
                Spacer(Modifier.width(12.dp))
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Color.Gray)
            }
            AnimatedVisibility(visible = open) {
                Column(Modifier.padding(bottom = 8.dp), content = content)
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 32.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.Gray)
    }
}

@Composable
internal fun HeaderStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFFBBD7F7), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun HomeScreen(db: BosDatabase, go: (Dest) -> Unit) {
    val customers by db.customers().observeCount().collectAsStateWithLifecycle(0)
    val balance by db.customers().observeTotalBalance().collectAsStateWithLifecycle(0L)
    val low by db.items().observeLowStockCount().collectAsStateWithLifecycle(0)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                .background(Brush.verticalGradient(listOf(BosBlueDark, BosBlue)))
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            HeaderStat("العملاء", customers.toString(), Modifier.weight(1f))
            HeaderStat("صافي الأرصدة", balance.money(), Modifier.weight(1.4f))
            HeaderStat("أصناف ناقصة", low.toString(), Modifier.weight(1f))
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("المبيعات", Icons.Filled.ShoppingCart) { go(Dest.SALES) }
                Tile("المشتريات", Icons.Filled.ShoppingBag) { go(Dest.PURCHASES) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile("قبض / صرف", Icons.Filled.SwapHoriz) { go(Dest.VOUCHERS) }
                Tile("الحسابات", Icons.Filled.AccountBalance) { go(Dest.CUSTOMERS) }
            }

            MenuSection("المخزون", Icons.Filled.Inventory2) {
                MenuRow("الأصناف والكميات") { go(Dest.ITEMS) }
                MenuRow("حركة صنف") { go(Dest.MOVES) }
                MenuRow("جرد المخزون وتسوية الفروقات") { go(Dest.COUNT) }
                MenuRow("المستودعات والمناقلة") { go(Dest.WAREHOUSES) }
            }
            MenuSection("الحسابات", Icons.Filled.AccountBalance) {
                MenuRow("العملاء والموردون وكشف الحساب") { go(Dest.CUSTOMERS) }
                MenuRow("سندات القبض والصرف") { go(Dest.VOUCHERS) }
                MenuRow("المستحقات ومواعيد السداد") { go(Dest.DUE) }
                MenuRow("المصروفات") { go(Dest.EXPENSES) }
            }
            MenuSection("التقارير", Icons.Filled.BarChart) {
                MenuRow("ميزان المراجعة") { go(Dest.TRIAL) }
                MenuRow("الأرباح والخسائر") { go(Dest.PNL) }
                MenuRow("سجل العمليات") { go(Dest.LOG) }
            }
            MenuSection("الإعدادات والأدوات", Icons.Filled.Settings) {
                MenuRow("بيانات المنشأة وقفل التطبيق") { go(Dest.SETTINGS) }
                MenuRow("العملات وأسعار الصرف") { go(Dest.CURRENCIES) }
                MenuRow("النسخ الاحتياطي والاستيراد") { go(Dest.BACKUP) }
            }
        }
    }
}
