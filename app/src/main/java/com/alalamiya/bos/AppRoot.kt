package com.alalamiya.bos

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alalamiya.bos.data.BosDatabase

enum class Dest(val title: String) {
    HOME("المحاسب الذهبي"),
    SALES("المبيعات"), PURCHASES("المشتريات"), QUOTES("عروض الأسعار"), ORDERS("طلبات الشراء"),
    VOUCHERS("قبض / صرف"), JOURNAL("القيد اليومي"), OPENING("القيد الافتتاحي"),
    ACCOUNTS("الحسابات"), ADD_ACCOUNT("إضافة حساب"), STATEMENT("كشف حساب"), ACC_STATEMENT("كشف حساب"),
    CASH("حركة الصندوق"), CHART("دليل الحسابات"), LIMITS("سقف الحساب"), EXPENSES("المصروفات"),
    ITEMS("الأصناف"), PRICES("أسعار البيع"), ITEM_UNITS("وحدات الصنف"), MOVES("حركة صنف"),
    STOCK_OPS("العمليات المخزنية"), MOVE_TRANSFER("تحويل مخزني"), MOVE_ADJUST("تسوية مخزنية"),
    MOVE_ISSUE("صرف مخزني"), MOVE_RECEIPT("توريد مخزني"), COUNT("جرد مخزني"),
    WAREHOUSES("المستودعات"), CURRENCIES("العملات"), DUE("المستحقات"),
    TRIAL("ميزان المراجعة"), PNL("الأرباح والخسائر"), BALANCE_SHEET("المركز المالي"),
    BAL_CUSTOMERS("أرصدة العملاء"), BAL_SUPPLIERS("أرصدة الموردين"), STOCK_REMAINING("المخزون المتبقي"),
    LOG("سجل العمليات"), SETTINGS("الإعدادات"), BACKUP("النسخ الاحتياطي"),
    CATEGORIES("التصنيفات"), GROUPS("مجموعة الصنف"), UNITS("وحدات القياس"), TAX("الضريبة")
}

@Composable
private fun StockOpsScreen(db: BosDatabase, go: (Dest) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { go(Dest.MOVE_TRANSFER) }, modifier = Modifier.weight(1f)) { Text("تحويل") }
                Button(onClick = { go(Dest.MOVE_RECEIPT) }, modifier = Modifier.weight(1f)) { Text("توريد") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { go(Dest.MOVE_ISSUE) }, modifier = Modifier.weight(1f)) { Text("صرف") }
                OutlinedButton(onClick = { go(Dest.MOVE_ADJUST) }, modifier = Modifier.weight(1f)) { Text("تسوية") }
            }
        }
        Box(Modifier.weight(1f)) { StockOpsListScreen(db) }
    }
}

@Composable
fun AppRoot(db: BosDatabase) {
    val ctx = LocalContext.current
    var dest by remember { mutableStateOf(Dest.HOME) }
    var statementId by remember { mutableStateOf<Long?>(null) }
    var accId by remember { mutableStateOf<Long?>(null) }
    var locked by remember { mutableStateOf(Prefs.lockEnabled(ctx)) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var stoppedAt = 0L
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) stoppedAt = System.currentTimeMillis()
            if (e == Lifecycle.Event.ON_START && stoppedAt > 0 &&
                System.currentTimeMillis() - stoppedAt > 30_000 && Prefs.lockEnabled(ctx)
            ) locked = true
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (locked) {
        LockScreen { locked = false }
    } else {
        val back = {
            dest = when (dest) {
                Dest.STATEMENT, Dest.ACC_STATEMENT, Dest.ADD_ACCOUNT -> Dest.ACCOUNTS
                Dest.MOVE_TRANSFER, Dest.MOVE_ADJUST, Dest.MOVE_ISSUE, Dest.MOVE_RECEIPT -> Dest.STOCK_OPS
                Dest.CATEGORIES, Dest.GROUPS, Dest.UNITS, Dest.TAX -> Dest.SETTINGS
                else -> Dest.HOME
            }
        }
        BackHandler(enabled = dest != Dest.HOME) { back() }
        val noBar = dest == Dest.SALES || dest == Dest.PURCHASES || dest == Dest.QUOTES || dest == Dest.ORDERS
        Scaffold(
            topBar = { if (!noBar) GradientBar(dest.title, if (dest == Dest.HOME) null else back) }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (dest) {
                    Dest.HOME -> HomeScreen(db) { dest = it }
                    Dest.SALES -> InvoicesScreen(db, "sales") { dest = Dest.HOME }
                    Dest.PURCHASES -> InvoicesScreen(db, "purchases") { dest = Dest.HOME }
                    Dest.QUOTES -> InvoicesScreen(db, "quotes") { dest = Dest.HOME }
                    Dest.ORDERS -> InvoicesScreen(db, "orders") { dest = Dest.HOME }
                    Dest.VOUCHERS -> VouchersScreen(db)
                    Dest.JOURNAL -> EntryListScreen(db, false)
                    Dest.OPENING -> EntryListScreen(db, true)
                    Dest.ACCOUNTS -> AccountsScreen(
                        db, onParty = { statementId = it; dest = Dest.STATEMENT },
                        onAccount = { accId = it; dest = Dest.ACC_STATEMENT }, onAdd = { dest = Dest.ADD_ACCOUNT }
                    )
                    Dest.ADD_ACCOUNT -> AddAccountScreen(db) { dest = Dest.ACCOUNTS }
                    Dest.STATEMENT -> statementId?.let { StatementScreen(db, it) }
                    Dest.ACC_STATEMENT -> accId?.let { AccountStatementScreen(db, it) }
                    Dest.CASH -> CashScreen(db)
                    Dest.CHART -> ChartScreen(db)
                    Dest.LIMITS -> LimitsScreen(db)
                    Dest.EXPENSES -> ExpensesScreen(db)
                    Dest.ITEMS -> ItemsScreen(db)
                    Dest.PRICES -> PricesScreen(db)
                    Dest.ITEM_UNITS -> ItemUnitsScreen(db)
                    Dest.MOVES -> MovementScreen(db)
                    Dest.STOCK_OPS -> StockOpsScreen(db) { dest = it }
                    Dest.MOVE_TRANSFER -> StockMoveScreen(db, "transfer") { dest = Dest.STOCK_OPS }
                    Dest.MOVE_ADJUST -> StockMoveScreen(db, "adjust") { dest = Dest.STOCK_OPS }
                    Dest.MOVE_ISSUE -> StockMoveScreen(db, "issue") { dest = Dest.STOCK_OPS }
                    Dest.MOVE_RECEIPT -> StockMoveScreen(db, "receipt") { dest = Dest.STOCK_OPS }
                    Dest.COUNT -> StockCountScreen(db)
                    Dest.WAREHOUSES -> WarehousesScreen(db)
                    Dest.CURRENCIES -> CurrenciesScreen(db)
                    Dest.DUE -> DueScreen(db) { statementId = it; dest = Dest.STATEMENT }
                    Dest.TRIAL -> TrialBalanceScreen(db)
                    Dest.PNL -> ProfitLossScreen(db)
                    Dest.BALANCE_SHEET -> BalanceSheetScreen(db)
                    Dest.BAL_CUSTOMERS -> BalancesScreen(db, 0) { statementId = it; dest = Dest.STATEMENT }
                    Dest.BAL_SUPPLIERS -> BalancesScreen(db, 1) { statementId = it; dest = Dest.STATEMENT }
                    Dest.STOCK_REMAINING -> StockRemainingScreen(db)
                    Dest.LOG -> LogScreen(db)
                    Dest.SETTINGS -> SettingsScreen { dest = it }
                    Dest.BACKUP -> BackupScreen(db)
                    Dest.CATEGORIES -> LookupScreen(db, 0)
                    Dest.GROUPS -> LookupScreen(db, 1)
                    Dest.UNITS -> LookupScreen(db, 2)
                    Dest.TAX -> TaxScreen(db)
                }
            }
        }
    }
}
