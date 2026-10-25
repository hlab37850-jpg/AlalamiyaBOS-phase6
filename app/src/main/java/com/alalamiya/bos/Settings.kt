package com.alalamiya.bos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Prefs.storeName(ctx)) }
    var phone by remember { mutableStateOf(Prefs.storePhone(ctx)) }
    var address by remember { mutableStateOf(Prefs.storeAddress(ctx)) }
    var lockOn by remember { mutableStateOf(Prefs.lockEnabled(ctx)) }
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(Prefs.autoBackup(ctx)) }
    var bioOn by remember { mutableStateOf(Prefs.biometric(ctx)) }
    var printerName by remember { mutableStateOf(Prefs.printerName(ctx)) }
    var paper by remember { mutableIntStateOf(Prefs.paperMm(ctx)) }
    var showPrinters by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) showPrinters = true }
    var msg by remember { mutableStateOf<String?>(null) }
    val numPin = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("بيانات المنشأة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("تظهر في رأس ملفات PDF والفواتير المطبوعة.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(name, { name = it }, label = { Text("اسم المنشأة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text("أرقام التواصل") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(address, { address = it }, label = { Text("العنوان") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { Prefs.setStore(ctx, name.trim(), phone.trim(), address.trim()); msg = "تم حفظ بيانات المنشأة" }) { Text("حفظ") }

        Text("قفل التطبيق", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (!lockOn) {
            OutlinedTextField(pin1, { pin1 = it.filter(Char::isDigit).take(8) }, label = { Text("رمز سري (4 إلى 8 أرقام)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = numPin, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pin2, { pin2 = it.filter(Char::isDigit).take(8) }, label = { Text("تأكيد الرمز") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = numPin, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                if (pin1.length < 4) msg = "الرمز يجب أن يكون 4 أرقام على الأقل"
                else if (pin1 != pin2) msg = "الرمزان غير متطابقين"
                else { Prefs.setPin(ctx, pin1); lockOn = true; pin1 = ""; pin2 = ""; msg = "تم تفعيل القفل" }
            }) { Text("تفعيل القفل") }
        } else {
            Text("القفل مفعّل ويُطلب الرمز عند فتح التطبيق وبعد غيابه 30 ثانية.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(cur, { cur = it.filter(Char::isDigit).take(8) }, label = { Text("الرمز الحالي") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = numPin, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                if (Prefs.checkPin(ctx, cur)) { Prefs.clearPin(ctx); lockOn = false; cur = ""; msg = "تم إلغاء القفل" } else msg = "الرمز غير صحيح"
            }) { Text("إلغاء القفل") }
            if (Biometric.available(ctx)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("السماح بفتح التطبيق بالبصمة", Modifier.weight(1f))
                    Switch(checked = bioOn, onCheckedChange = { bioOn = it; Prefs.setBiometric(ctx, it) })
                }
            }
        }

        Text("النسخ الاحتياطي التلقائي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("نسخة يومية داخل التطبيق (آخر 7 أيام)", Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = { auto = it; Prefs.setAutoBackup(ctx, it); Background.setAutoBackup(ctx, it) })
        }
        Text("النسخ التلقائية تُحفظ داخل التطبيق وتُحذف عند إزالته، فصدّر نسخة خارج الجهاز بشكل دوري من «النسخ الاحتياطي».", style = MaterialTheme.typography.bodySmall)

        Text("الطابعة الحرارية (بلوتوث)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(if (printerName.isBlank()) "لم تُختر طابعة بعد. اقرن الطابعة من إعدادات بلوتوث الجهاز أولًا." else "الطابعة المختارة: $printerName", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            if (Thermal.hasPermission(ctx)) showPrinters = true else askBt.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }) { Text("اختيار طابعة مقترنة") }
        if (showPrinters) {
            val devices = Thermal.pairedDevices(ctx)
            if (devices.isEmpty()) Text("لا توجد أجهزة مقترنة.", style = MaterialTheme.typography.bodySmall)
            devices.forEach { (n, a) ->
                TextButton(onClick = { Prefs.setPrinter(ctx, n, a); printerName = n; showPrinters = false }) { Text(n) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = paper < 80, onClick = { paper = 58; Prefs.setPaperMm(ctx, 58) }, label = { Text("ورق 58 مم") })
            FilterChip(selected = paper >= 80, onClick = { paper = 80; Prefs.setPaperMm(ctx, 80) }, label = { Text("ورق 80 مم") })
        }
        Button(enabled = printerName.isNotBlank(), onClick = {
            scope.launch {
                val spec = PdfSpec(
                    fileName = "", title = "اختبار الطباعة", subtitle = Prefs.header(ctx),
                    headers = listOf("", "", "", ""), rows = listOf(listOf("صنف تجريبي", "2", "5", "10")),
                    footer = listOf("الإجمالي: 10")
                )
                val m = withContext(Dispatchers.IO) { Thermal.print(ctx, spec) }
                Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
            }
        }) { Text("طباعة تجريبية") }

        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableIntStateOf(0) }
    var blockedUntil by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    val act = ctx as? FragmentActivity
    val bio = Prefs.biometric(ctx) && Biometric.available(ctx) && act != null
    LaunchedEffect(Unit) { if (bio) Biometric.prompt(act!!, onUnlock) }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BosBlue, BosBlueDark))), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("المحاسب الذهبي", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("أدخل الرمز السري", color = Color.White)
            OutlinedTextField(
                pin, { pin = it.filter(Char::isDigit).take(8) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFFBBD7F7), cursorColor = Color.White
                )
            )
            error?.let { Text(it, color = BosGold) }
            Button(
                colors = ButtonDefaults.buttonColors(containerColor = BosGold, contentColor = Color.Black),
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now < blockedUntil) {
                        error = "محاولات كثيرة، انتظر ${(blockedUntil - now) / 1000 + 1} ثانية"
                    } else if (Prefs.checkPin(ctx, pin)) {
                        onUnlock()
                    } else {
                        wrong++
                        pin = ""
                        if (wrong >= 5) { blockedUntil = now + 30_000; wrong = 0; error = "محاولات كثيرة، انتظر 30 ثانية" } else error = "الرمز غير صحيح"
                    }
                }
            ) { Text("فتح") }
            if (bio) TextButton(onClick = { Biometric.prompt(act!!, onUnlock) }) { Text("استخدام البصمة", color = Color.White) }
        }
    }
}
