package com.alalamiya.bos.ui

import java.util.Locale

/** يحوّل القيمة المخزنة (×1000) إلى نص مقروء بفواصل الآلاف. */
fun Long.money(): String {
    val s = String.format(Locale.US, "%,.3f", this / 1000.0)
    return s.trimEnd('0').trimEnd('.')
}

/** يحوّل نصًا مُدخلًا إلى القيمة المخزنة، أو null إذا كان غير صالح. */
fun String.toMilli(): Long? {
    val t = trim().replace(",", "").replace("٫", ".")
    if (t.isEmpty()) return 0
    return t.toBigDecimalOrNull()?.movePointRight(3)?.toLong()
}
