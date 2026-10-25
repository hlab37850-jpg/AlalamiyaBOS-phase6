package com.alalamiya.bos

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.alalamiya.bos.data.BosDatabase

enum class Dest(val title: String) {
    HOME("المحاسب الذهبي"),
    SALES("المبيعات"),
    PURCHASES("المشتريات"),
    VOUCHERS("قبض / صرف"),
    CUSTOMERS("الحسابات"),
    STATEMENT("كشف حساب"),
    ITEMS("الأصناف"),
    MOVES("حركة صنف"),
    COUNT("جرد المخزون"),
    WAREHOUSES("المستودعات"),
    CURRENCIES("العملات"),
    EXPENSES("المصروفات"),
    DUE("المستحقات"),
    TRIAL("ميزان المراجعة"),
    PNL("الأرباح والخسائر"),
    LOG("سجل العمليات"),
    SETTINGS("الإعدادات"),
    BACKUP("النسخ الاحتياطي")
}

@Composable
fun AppRoot(db: BosDatabase) {
    val ctx = LocalContext.current
    var dest by remember { mutableStateOf(Dest.HOME) }
    var statementId by remember { mutableStateOf<Long?>(null) }
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
        val back = { dest = if (dest == Dest.STATEMENT) Dest.CUSTOMERS else Dest.HOME }
        BackHandler(enabled = dest != Dest.HOME) { back() }
        Scaffold(
            topBar = { if (dest != Dest.SALES && dest != Dest.PURCHASES) GradientBar(dest.title, if (dest == Dest.HOME) null else back) }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (dest) {
                    Dest.HOME -> HomeScreen(db) { dest = it }
                    Dest.SALES -> InvoicesScreen(db, "sales") { dest = Dest.HOME }
                    Dest.PURCHASES -> InvoicesScreen(db, "purchases") { dest = Dest.HOME }
                    Dest.VOUCHERS -> VouchersScreen(db)
                    Dest.CUSTOMERS -> CustomersScreen(db) { statementId = it; dest = Dest.STATEMENT }
                    Dest.STATEMENT -> statementId?.let { StatementScreen(db, it) }
                    Dest.ITEMS -> ItemsScreen(db)
                    Dest.MOVES -> MovementScreen(db)
                    Dest.COUNT -> StockCountScreen(db)
                    Dest.WAREHOUSES -> WarehousesScreen(db)
                    Dest.CURRENCIES -> CurrenciesScreen(db)
                    Dest.EXPENSES -> ExpensesScreen(db)
                    Dest.DUE -> DueScreen(db) { statementId = it; dest = Dest.STATEMENT }
                    Dest.TRIAL -> TrialBalanceScreen(db)
                    Dest.PNL -> ProfitLossScreen(db)
                    Dest.LOG -> LogScreen(db)
                    Dest.SETTINGS -> SettingsScreen()
                    Dest.BACKUP -> BackupScreen(db)
                }
            }
        }
    }
}
