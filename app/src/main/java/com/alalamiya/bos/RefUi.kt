package com.alalamiya.bos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** عناصر واجهة مطابقة لتطبيق المحاسب المرجعي: حقول بخط سفلي، قوائم منسدلة بنص مسطَّر، بطاقات بإطار أزرق. */
val RefBlue = Color(0xFF1565C0)
val RefPink = Color(0xFFFF4081)
val RefBg = Color(0xFFFAFAFA)
private val RefHint = Color(0xFF8E8E8E)
private val RefLine = Color(0xFF9E9E9E)

@Composable
fun RefBar(onBack: () -> Unit, middle: @Composable RowScope.() -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxWidth().background(BarBrush)) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = Color.White) }
                middle()
                actions()
            }
        }
    }
}

@Composable
fun RefCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().border(2.dp, RefBlue, RoundedCornerShape(8.dp)).padding(8.dp)) { content() }
}

@Composable
fun UnderlineField(
    value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, readOnly: Boolean = false, centerHint: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            if (value.isEmpty()) {
                Text(hint, color = RefHint, fontSize = 18.sp, modifier = Modifier.align(if (centerHint) Alignment.Center else Alignment.CenterStart))
            }
            BasicTextField(
                value = value, onValueChange = onChange, singleLine = true, readOnly = readOnly,
                textStyle = TextStyle(fontSize = 18.sp, color = Color.Black, textAlign = if (centerHint) TextAlign.Center else TextAlign.Start),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                cursorBrush = SolidColor(RefPink),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            )
        }
        Box(Modifier.fillMaxWidth().height(if (focused) 2.dp else 1.dp).background(if (focused) RefPink else RefLine))
    }
}

@Composable
fun NumField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, isError: Boolean = false) {
    Column(modifier) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, color = if (isError) Color(0xFFB3261E) else Color.Black, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            cursorBrush = SolidColor(RefPink),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (isError) Color(0xFFB3261E) else RefLine))
    }
}

@Composable
fun Underlined(text: String, modifier: Modifier = Modifier, bold: Boolean = false) {
    Text(
        text, modifier, fontSize = 17.sp, textDecoration = TextDecoration.Underline,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
    )
}

@Composable
fun Spinner(
    text: String, options: List<String>, onPick: (Int) -> Unit, modifier: Modifier = Modifier, bold: Boolean = false,
    extra: List<String> = emptyList(), onExtra: (Int) -> Unit = {}
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(Modifier.clickable { open = true }, verticalAlignment = Alignment.CenterVertically) {
            Underlined(text, bold = bold)
            Icon(Icons.Filled.ArrowDropDown, null, tint = Color.DarkGray)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(i); open = false }) }
            extra.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o, color = RefBlue) }, onClick = { onExtra(i); open = false }) }
        }
    }
}

@Composable
fun PlusCircle(onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false) {
    Box(
        modifier.size(42.dp).clip(CircleShape)
            .background(if (filled) Color.White else Color.Transparent)
            .then(if (filled) Modifier else Modifier.border(2.dp, RefBlue, CircleShape))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Add, "إضافة", tint = if (filled) Color(0xFF1B4F8F) else RefBlue, modifier = Modifier.size(28.dp))
    }
}
