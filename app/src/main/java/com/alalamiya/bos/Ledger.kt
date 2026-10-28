package com.alalamiya.bos

import androidx.room.withTransaction
import com.alalamiya.bos.data.Account
import com.alalamiya.bos.data.AppSetting
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Customer
import com.alalamiya.bos.data.Invoice
import com.alalamiya.bos.data.InvoiceLine
import com.alalamiya.bos.data.ItemStock
import com.alalamiya.bos.data.JournalEntry
import com.alalamiya.bos.data.JournalLine
import com.alalamiya.bos.data.JournalParty
import com.alalamiya.bos.data.OperationLog
import com.alalamiya.bos.data.StockMove
import com.alalamiya.bos.data.StockMoveLine
import com.alalamiya.bos.data.Voucher
import com.alalamiya.bos.data.VoucherLine
import java.math.BigInteger
import kotlin.math.abs

/** سطر فاتورة: الكمية والسعر بوحدة السطر (الأساسية أو فرعية بمعامل factor ×1000). */
data class InvoiceLineInput(val itemId: Long, val quantity: Long, val price: Long, val unitName: String = "", val factor: Long = 1000L)

/** سطر سند: إما طرف (عميل/مورد) أو حساب من دليل الحسابات. */
data class VLineIn(val partyId: Long?, val accountId: Long?, val amount: Long, val note: String)

/** مرجع حساب في القيود اليدوية. */
data class AccRef(val partyId: Long? = null, val accountId: Long? = null)

data class MoveLineIn(val itemId: Long, val quantity: Long, val cost: Long = 0)

/**
 * المحرك المحاسبي: كل عملية مالية تنتج قيدًا مزدوجًا متوازنًا وتحدّث الرصيد والمخزون في معاملة واحدة
 * (إما تنجح كلها أو لا يتغير شيء). الرصيد الموجب = على الطرف (مدين)، والسالب = له (دائن).
 * كل المبالغ بالعملة الأساسية؛ الفواتير بعملة أخرى تُحوَّل عند الحفظ بسعر الصرف المسجَّل.
 */
class Ledger(private val db: BosDatabase) {

    companion object {
        private fun mulDiv(a: Long, b: Long, d: Long): Long =
            BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
                .add(BigInteger.valueOf(d / 2)).divide(BigInteger.valueOf(d)).toLong()

        fun lineTotal(q: Long, p: Long): Long = mulDiv(q, p, 1000)
        fun convert(x: Long, rate: Long): Long = mulDiv(x, rate, 1000)
        fun fromBase(x: Long, rate: Long): Long = mulDiv(x, 1000, rate)
        fun percentOf(base: Long, pctMilli: Long): Long = mulDiv(base, pctMilli, 100000)
        /** كمية السطر بالوحدة الأساسية. */
        fun baseQty(q: Long, factor: Long): Long = mulDiv(q, factor, 1000)
        /** سعر السطر محوَّلًا إلى سعر الوحدة الأساسية. */
        fun basePrice(p: Long, factor: Long): Long = mulDiv(p, 1000, factor)

        val INVOICE_TYPES = setOf("sale", "purchase", "sale_return", "purchase_return")
        val DOC_TYPES = setOf("quote", "purchase_order")
    }

    // ───────────── أدوات داخلية ─────────────

    private suspend fun account(code: String, name: String, type: String): Long {
        db.accounts().findByCode(code)?.let { return it.id }
        return db.accounts().insert(Account(code = code, name = name, type = type))
    }

    private suspend fun line(entryId: Long, accountId: Long, debit: Long, credit: Long) {
        if (debit + credit > 0) db.journal().insertLine(JournalLine(entryId = entryId, accountId = accountId, debit = debit, credit = credit))
    }

    /** يمنع أي عملية بتاريخ داخل سنة مقفلة. */
    suspend fun checkOpen(date: Long) {
        val closed = db.settings().get("closed_until")?.toLongOrNull() ?: 0L
        if (date <= closed) error("السنة المالية مقفلة لهذا التاريخ")
    }

    private fun partyCode(p: Customer) = if (p.kind == 1) "2100" else "1200"
    private fun partyAccName(p: Customer) = if (p.kind == 1) "الموردون" else "العملاء"
    private fun partyAccType(p: Customer) = if (p.kind == 1) "liability" else "asset"

    /** يغيّر رصيد طرف مع فحص السقف (checkLimit=false عند العكس/الحذف). */
    private suspend fun applyParty(p: Customer, delta: Long, now: Long, checkLimit: Boolean = true): Customer {
        val nb = p.balance + delta
        if (checkLimit) {
            if (delta > 0 && p.debitLimit > 0 && nb > p.debitLimit) error("تجاوز سقف المديونية للحساب «${p.name}»")
            if (delta < 0 && p.creditLimit > 0 && -nb > p.creditLimit) error("تجاوز سقف الدائنية للحساب «${p.name}»")
        }
        val u = p.copy(balance = nb, lastContactAt = now)
        db.customers().update(u)
        return u
    }

    /** يغيّر مخزون الصنف في مستودع، ويُبقي Item.quantity مساويًا لمجموع المستودعات. */
    private suspend fun changeStock(itemId: Long, wid: Long, delta: Long, newCost: Long? = null) {
        val item = db.items().findById(itemId) ?: error("صنف غير موجود")
        val row = db.stock().get(itemId, wid)
        db.stock().upsert(ItemStock(itemId, wid, (row?.quantity ?: 0L) + delta))
        db.items().update(item.copy(quantity = item.quantity + delta, costPrice = newCost ?: item.costPrice))
    }

    suspend fun setting(key: String): String? = db.settings().get(key)
    suspend fun putSetting(key: String, value: String) = db.settings().put(AppSetting(key, value))

    // ───────────── سند قبض/صرف لطرف واحد (النمط القديم) ─────────────

    suspend fun postVoucher(type: String, customerId: Long, amount: Long, note: String) {
        postVoucherMulti(type == "receipt", "1100", System.currentTimeMillis(), note, listOf(VLineIn(customerId, null, amount, "")))
    }

    // ───────────── سند قبض/صرف متعدد القيود ─────────────

    /** receipt: يدخل المبلغ إلى الصندوق ويُقيَّد دائنًا على كل سطر. payment: العكس. */
    suspend fun postVoucherMulti(receipt: Boolean, fundCode: String, date: Long, remarks: String, lines: List<VLineIn>): Long {
        require(lines.isNotEmpty()) { "أضف سطرًا واحدًا على الأقل" }
        require(lines.all { it.amount > 0 && (it.partyId != null) != (it.accountId != null) }) { "سطر سند غير صالح" }
        return db.withTransaction {
            checkOpen(date)
            val total = lines.sumOf { it.amount }
            val title = if (receipt) "سند قبض" else "سند صرف"
            val vid = db.vouchers().insert(
                Voucher(type = if (receipt) "receipt" else "payment", customerId = null, amount = total, date = date, note = remarks, fundCode = fundCode)
            )
            val eid = db.journal().insertEntry(
                JournalEntry(date = date, description = "$title #$vid" + if (remarks.isNotBlank()) " — $remarks" else "", refType = "voucher", refId = vid)
            )
            val fund = account(fundCode, "الصندوق", "asset")
            if (receipt) line(eid, fund, total, 0) else line(eid, fund, 0, total)
            for (l in lines) {
                db.vouchers().insertLine(VoucherLine(voucherId = vid, partyId = l.partyId, accountId = l.accountId, amount = l.amount, note = l.note))
                if (l.partyId != null) {
                    val p = db.customers().findById(l.partyId) ?: error("الطرف غير موجود")
                    val acc = account(partyCode(p), partyAccName(p), partyAccType(p))
                    val delta = if (receipt) -l.amount else l.amount
                    if (receipt) line(eid, acc, 0, l.amount) else line(eid, acc, l.amount, 0)
                    applyParty(p, delta, date)
                    db.journalParty().insert(JournalParty(entryId = eid, partyId = p.id, delta = delta))
                } else {
                    val accId = l.accountId ?: error("حساب غير صالح")
                    if (receipt) line(eid, accId, 0, l.amount) else line(eid, accId, l.amount, 0)
                }
            }
            db.log().insert(OperationLog(action = if (receipt) "voucher_receipt" else "voucher_payment", details = "#$vid: $total"))
            vid
        }
    }

    suspend fun deleteVoucher(id: Long) {
        db.withTransaction {
            val v = db.vouchers().findById(id) ?: error("السند غير موجود")
            checkOpen(v.date)
            val now = System.currentTimeMillis()
            val entry = db.journal().entryFor("voucher", id)
            val parties = if (entry != null) db.journalParty().forEntry(entry.id) else emptyList()
            if (parties.isNotEmpty()) {
                for (jp in parties) db.customers().findById(jp.partyId)?.let { applyParty(it, -jp.delta, now, false) }
            } else if (v.customerId != null) {   // سند قديم لطرف واحد
                db.customers().findById(v.customerId)?.let { applyParty(it, if (v.type == "receipt") v.amount else -v.amount, now, false) }
            }
            if (entry != null) {
                db.journalParty().deleteForEntry(entry.id)
                db.journal().deleteLinesOf(entry.id)
                db.journal().deleteEntry(entry.id)
            }
            db.vouchers().deleteLines(id)
            db.vouchers().delete(id)
            db.log().insert(OperationLog(action = "voucher_delete", details = "#$id"))
        }
    }

    // ───────────── القيد اليومي والافتتاحي ─────────────

    private data class Resolved(val accountId: Long, val party: Customer?)

    private suspend fun resolve(r: AccRef): Resolved {
        if (r.partyId != null) {
            val p = db.customers().findById(r.partyId) ?: error("الحساب غير موجود")
            return Resolved(account(partyCode(p), partyAccName(p), partyAccType(p)), p)
        }
        val id = r.accountId ?: error("اختر الحساب")
        db.accounts().findById(id) ?: error("الحساب غير موجود")
        return Resolved(id, null)
    }

    /** قيد يومي: من حساب (مدين) إلى حساب (دائن). */
    suspend fun postJournal(debit: AccRef, credit: AccRef, amount: Long, date: Long, remarks: String): Long {
        require(amount > 0) { "المبلغ يجب أن يكون أكبر من صفر" }
        return db.withTransaction {
            checkOpen(date)
            val d = resolve(debit)
            val c = resolve(credit)
            require(d.accountId != c.accountId || d.party?.id != c.party?.id) { "اختر حسابين مختلفين" }
            val eid = db.journal().insertEntry(JournalEntry(date = date, description = remarks.ifBlank { "قيد يومي" }, refType = "journal", refId = 0))
            line(eid, d.accountId, amount, 0)
            line(eid, c.accountId, 0, amount)
            d.party?.let { applyParty(it, amount, date); db.journalParty().insert(JournalParty(entryId = eid, partyId = it.id, delta = amount)) }
            c.party?.let { p ->
                val fresh = db.customers().findById(p.id) ?: p
                applyParty(fresh, -amount, date); db.journalParty().insert(JournalParty(entryId = eid, partyId = p.id, delta = -amount))
            }
            db.log().insert(OperationLog(action = "journal", details = "$amount"))
            eid
        }
    }

    /** قيد افتتاحي: رصيد بداية لحساب مقابل «أرصدة افتتاحية». */
    suspend fun postOpening(ref: AccRef, amount: Long, debit: Boolean, date: Long): Long {
        require(amount > 0) { "المبلغ يجب أن يكون أكبر من صفر" }
        return db.withTransaction {
            checkOpen(date)
            val r = resolve(ref)
            val counter = account("3200", "أرصدة افتتاحية", "equity")
            val eid = db.journal().insertEntry(JournalEntry(date = date, description = "قيد إفتتاحي", refType = "opening", refId = 0))
            if (debit) { line(eid, r.accountId, amount, 0); line(eid, counter, 0, amount) }
            else { line(eid, r.accountId, 0, amount); line(eid, counter, amount, 0) }
            r.party?.let {
                val delta = if (debit) amount else -amount
                applyParty(it, delta, date, false); db.journalParty().insert(JournalParty(entryId = eid, partyId = it.id, delta = delta))
            }
            db.log().insert(OperationLog(action = "opening", details = "$amount"))
            eid
        }
    }

    /** حذف قيد يومي أو افتتاحي (يُعكس أثره على أرصدة الأطراف). */
    suspend fun deleteManualEntry(entryId: Long) {
        db.withTransaction {
            val e = db.journal().entryById(entryId) ?: error("القيد غير موجود")
            require(e.refType == "journal" || e.refType == "opening") { "لا يمكن حذف هذا النوع من القيود من هنا" }
            checkOpen(e.date)
            val now = System.currentTimeMillis()
            for (jp in db.journalParty().forEntry(entryId)) db.customers().findById(jp.partyId)?.let { applyParty(it, -jp.delta, now, false) }
            db.journalParty().deleteForEntry(entryId)
            db.journal().deleteLinesOf(entryId)
            db.journal().deleteEntry(entryId)
            db.log().insert(OperationLog(action = "entry_delete", details = "#$entryId"))
        }
    }

    // ───────────── الفواتير ─────────────

    private fun partyDeltaFor(type: String, rest: Long) = if (type == "sale" || type == "purchase_return") rest else -rest

    /**
     * فاتورة بيع / شراء / مرتجع بيع / مرتجع شراء. الأسعار والخصم والضريبة والرسوم والمدفوع بعملة الفاتورة (rate ×1000)
     * وتُحفظ كلها بالأساسية. paid = null يعني دفع كامل الصافي. replaceId ≠ 0 يعني تعديل فاتورة محفوظة بنفس رقمها.
     */
    suspend fun postInvoice(
        type: String, partyId: Long?, lines: List<InvoiceLineInput>, paid: Long?, discount: Long,
        tax: Long, fees: Long, note: String, warehouseId: Long, currency: String, rate: Long,
        date: Long, cashCode: String, replaceId: Long = 0L
    ): Long {
        require(type in INVOICE_TYPES) { "نوع فاتورة غير معروف" }
        require(lines.isNotEmpty()) { "الفاتورة بلا أصناف" }
        require(lines.all { it.quantity > 0 && it.price >= 0 && it.factor > 0 }) { "كمية أو سعر غير صالح" }
        require(rate > 0) { "سعر الصرف غير صالح" }
        require(discount >= 0 && tax >= 0 && fees >= 0 && (paid == null || paid >= 0)) { "قيمة غير صالحة" }

        val baseLines = lines.map { it.copy(price = convert(it.price, rate)) }
        val gross = baseLines.sumOf { lineTotal(it.quantity, it.price) }
        val disc = minOf(convert(discount, rate), gross)
        val taxBase = convert(tax, rate)
        val feesBase = convert(fees, rate)
        val net = gross - disc + taxBase + feesBase
        val paidBase = if (paid == null) net else minOf(convert(paid, rate), net)
        require(partyId != null || paidBase == net) { "الفاتورة الآجلة تحتاج اختيار طرف" }

        return db.withTransaction {
            checkOpen(date)
            if (replaceId != 0L) deleteInvoice(replaceId)
            val party = partyId?.let { db.customers().findById(it) ?: error("الطرف غير موجود") }
            val invId = db.invoices().insert(
                Invoice(
                    id = replaceId, type = type, customerId = partyId, date = date, total = net, paid = paidBase, note = note,
                    discount = disc, warehouseId = warehouseId, currency = currency, rate = rate, tax = taxBase, fees = feesBase
                )
            )

            val stockSign = if (type == "sale" || type == "purchase_return") -1 else 1
            var cost = 0L
            for (l in baseLines) {
                val item = db.items().findById(l.itemId) ?: error("صنف غير موجود")
                val bq = baseQty(l.quantity, l.factor)
                db.invoices().insertLine(
                    InvoiceLine(invoiceId = invId, itemId = l.itemId, quantity = l.quantity, price = l.price, factor = l.factor, unitName = l.unitName)
                )
                cost += lineTotal(bq, item.costPrice)
                changeStock(l.itemId, warehouseId, stockSign * bq, if (type == "purchase" && bq > 0) basePrice(l.price, l.factor) else null)
            }

            val label = when (type) {
                "sale" -> "فاتورة بيع"; "purchase" -> "فاتورة شراء"
                "sale_return" -> "مرتجع بيع"; else -> "مرتجع شراء"
            }
            val eid = db.journal().insertEntry(
                JournalEntry(date = date, description = "$label #$invId" + (party?.let { " - ${it.name}" } ?: ""), refType = "invoice", refId = invId)
            )
            val rest = net - paidBase
            suspend fun put(code: String, name: String, kind: String, debit: Long, credit: Long) {
                if (debit + credit > 0) line(eid, account(code, name, kind), debit, credit)
            }
            when (type) {
                "sale" -> {
                    put(cashCode, "الصندوق", "asset", paidBase, 0)
                    put("1200", "العملاء", "asset", rest, 0)
                    put("4300", "خصم مسموح به", "income", disc, 0)
                    put("4100", "المبيعات", "income", 0, gross)
                    put("2200", "ضريبة المبيعات المستحقة", "liability", 0, taxBase)
                    put("4500", "رسوم وإيرادات أخرى", "income", 0, feesBase)
                    put("5100", "تكلفة البضاعة المباعة", "expense", cost, 0)
                    put("1300", "المخزون", "asset", 0, cost)
                }
                "sale_return" -> {
                    put("4200", "مردودات المبيعات", "income", gross, 0)
                    put("2200", "ضريبة المبيعات المستحقة", "liability", taxBase, 0)
                    put("4500", "رسوم وإيرادات أخرى", "income", feesBase, 0)
                    put("4300", "خصم مسموح به", "income", 0, disc)
                    put(cashCode, "الصندوق", "asset", 0, paidBase)
                    put("1200", "العملاء", "asset", 0, rest)
                    put("1300", "المخزون", "asset", cost, 0)
                    put("5100", "تكلفة البضاعة المباعة", "expense", 0, cost)
                }
                "purchase" -> {
                    put("1300", "المخزون", "asset", gross, 0)
                    put("1400", "ضريبة المشتريات (مدخلات)", "asset", taxBase, 0)
                    put("5300", "رسوم المشتريات", "expense", feesBase, 0)
                    put(cashCode, "الصندوق", "asset", 0, paidBase)
                    put("2100", "الموردون", "liability", 0, rest)
                    put("4400", "خصم مكتسب", "income", 0, disc)
                }
                else -> {
                    put(cashCode, "الصندوق", "asset", paidBase, 0)
                    put("2100", "الموردون", "liability", rest, 0)
                    put("4400", "خصم مكتسب", "income", disc, 0)
                    put("1300", "المخزون", "asset", 0, gross)
                    put("1400", "ضريبة المشتريات (مدخلات)", "asset", 0, taxBase)
                    put("5300", "رسوم المشتريات", "expense", 0, feesBase)
                }
            }

            if (party != null && rest != 0L) applyParty(party, partyDeltaFor(type, rest), date)
            db.log().insert(OperationLog(action = "invoice_$type", details = "#$invId total=$net paid=$paidBase"))
            invId
        }
    }

    /** عرض سعر / طلب شراء: يُحفظ كمستند فقط دون أثر على المخزون أو الحسابات. */
    suspend fun postDocument(
        type: String, partyId: Long?, lines: List<InvoiceLineInput>, discount: Long, tax: Long, fees: Long,
        note: String, warehouseId: Long, currency: String, rate: Long, date: Long, replaceId: Long = 0L
    ): Long {
        require(type in DOC_TYPES) { "نوع مستند غير معروف" }
        require(lines.isNotEmpty()) { "المستند بلا أصناف" }
        require(lines.all { it.quantity > 0 && it.price >= 0 && it.factor > 0 }) { "كمية أو سعر غير صالح" }
        require(rate > 0) { "سعر الصرف غير صالح" }
        val baseLines = lines.map { it.copy(price = convert(it.price, rate)) }
        val gross = baseLines.sumOf { lineTotal(it.quantity, it.price) }
        val disc = minOf(convert(discount, rate), gross)
        val taxBase = convert(tax, rate)
        val feesBase = convert(fees, rate)
        return db.withTransaction {
            if (replaceId != 0L) { db.invoices().deleteLines(replaceId); db.invoices().delete(replaceId) }
            val id = db.invoices().insert(
                Invoice(
                    id = replaceId, type = type, customerId = partyId, date = date, total = gross - disc + taxBase + feesBase, paid = 0, note = note,
                    discount = disc, warehouseId = warehouseId, currency = currency, rate = rate, tax = taxBase, fees = feesBase
                )
            )
            for (l in baseLines) {
                db.invoices().insertLine(InvoiceLine(invoiceId = id, itemId = l.itemId, quantity = l.quantity, price = l.price, factor = l.factor, unitName = l.unitName))
            }
            db.log().insert(OperationLog(action = "doc_$type", details = "#$id"))
            id
        }
    }

    /** تحويل عرض سعر إلى فاتورة بيع (أو طلب شراء إلى فاتورة شراء): آجلة إن وُجد طرف، نقدية إن لم يوجد. */
    suspend fun convertDocument(docId: Long): Long {
        return db.withTransaction {
            val d = db.invoices().findById(docId) ?: error("المستند غير موجود")
            require(d.type in DOC_TYPES) { "هذا ليس مستندًا قابلًا للتحويل" }
            val inputs = db.invoices().lines(docId).map { InvoiceLineInput(it.itemId, it.quantity, it.price, it.unitName, it.factor) }
            val target = if (d.type == "quote") "sale" else "purchase"
            val id = postInvoice(
                target, d.customerId, inputs, if (d.customerId == null) null else 0L, d.discount, d.tax, d.fees,
                d.note, d.warehouseId, "YER", 1000L, System.currentTimeMillis(), "1100"
            )
            db.invoices().deleteLines(docId)
            db.invoices().delete(docId)
            id
        }
    }

    /** يعكس أثر فاتورة (مخزون + رصيد طرف) دون المساس بالقيود. */
    private suspend fun reverseEffects(inv: Invoice) {
        val now = System.currentTimeMillis()
        val stockSign = if (inv.type == "sale" || inv.type == "purchase_return") 1 else -1
        for (l in db.invoices().lines(inv.id)) {
            if (db.items().findById(l.itemId) == null) continue
            changeStock(l.itemId, inv.warehouseId, stockSign * baseQty(l.quantity, l.factor))
        }
        val rest = inv.total - inv.paid
        val cid = inv.customerId
        if (cid != null && rest != 0L) {
            val p = db.customers().findById(cid)
            if (p != null) applyParty(p, -partyDeltaFor(inv.type, rest), now, false)
        }
    }

    /** إلغاء فاتورة: يُعكس أثرها وتُنشأ قيود عكسية، وتبقى ظاهرة بحالة «ملغاة». */
    suspend fun cancelInvoice(id: Long) {
        db.withTransaction {
            val inv = db.invoices().findById(id) ?: error("الفاتورة غير موجودة")
            if (inv.cancelled) error("الفاتورة ملغاة مسبقًا")
            val now = System.currentTimeMillis()
            checkOpen(now)
            reverseEffects(inv)
            val orig = db.journal().entryFor("invoice", id)
            if (orig != null) {
                val eid = db.journal().insertEntry(JournalEntry(date = now, description = "إلغاء: ${orig.description}", refType = "invoice_cancel", refId = id))
                for (jl in db.journal().linesFor(orig.id)) line(eid, jl.accountId, jl.credit, jl.debit)
            }
            db.invoices().markCancelled(id)
            db.log().insert(OperationLog(action = "invoice_cancel", details = "#$id"))
        }
    }

    /** حذف فاتورة نهائيًا مع قيودها (يُعكس أثرها أولًا إن لم تكن ملغاة). */
    suspend fun deleteInvoice(id: Long) {
        db.withTransaction {
            val inv = db.invoices().findById(id) ?: error("الفاتورة غير موجودة")
            checkOpen(inv.date)
            if (inv.type !in DOC_TYPES) {
                if (!inv.cancelled) reverseEffects(inv)
                db.journal().deleteLinesByRef("invoice", "invoice_cancel", id)
                db.journal().deleteEntriesByRef("invoice", "invoice_cancel", id)
            }
            db.invoices().deleteLines(id)
            db.invoices().delete(id)
            db.log().insert(OperationLog(action = "invoice_delete", details = "#$id"))
        }
    }

    // ───────────── عمليات المخزون ─────────────

    /**
     * type: transfer (من wid إلى toWid) | receipt (توريد) | issue (صرف) | adjust (تسوية، الكمية موجبة=زيادة وسالبة=عجز).
     * الكميات بالوحدة الأساسية. تُنشأ قيود التوريد والصرف والتسوية بسعر التكلفة؛ التحويل بلا قيد.
     */
    suspend fun postStockMove(type: String, wid: Long, toWid: Long?, lines: List<MoveLineIn>, remarks: String, date: Long = System.currentTimeMillis()): Long {
        require(type in setOf("transfer", "receipt", "issue", "adjust")) { "نوع عملية غير معروف" }
        require(lines.isNotEmpty()) { "أضف صنفًا واحدًا على الأقل" }
        require(lines.all { it.quantity != 0L && (type == "adjust" || it.quantity > 0) }) { "كمية غير صالحة" }
        if (type == "transfer") require(toWid != null && toWid != wid) { "اختر مستودعًا مختلفًا للتحويل إليه" }
        return db.withTransaction {
            checkOpen(date)
            val mid = db.stockMoves().insert(StockMove(type = type, date = date, warehouseId = wid, toWarehouseId = toWid, remarks = remarks))
            var inValue = 0L
            var outValue = 0L
            for (l in lines) {
                val item = db.items().findById(l.itemId) ?: error("صنف غير موجود")
                val unitCost = if (l.cost > 0) l.cost else item.costPrice
                db.stockMoves().insertLine(StockMoveLine(moveId = mid, itemId = l.itemId, quantity = l.quantity, cost = unitCost))
                val value = lineTotal(abs(l.quantity), unitCost)
                when (type) {
                    "transfer" -> {
                        val available = db.stock().get(l.itemId, wid)?.quantity ?: 0L
                        if (available < l.quantity) error("الكمية غير كافية في المستودع المصدر للصنف «${item.name}»")
                        changeStock(l.itemId, wid, -l.quantity)
                        changeStock(l.itemId, toWid ?: wid, l.quantity)
                    }
                    "receipt" -> { changeStock(l.itemId, wid, l.quantity, if (l.cost > 0) l.cost else null); inValue += value }
                    "issue" -> { changeStock(l.itemId, wid, -l.quantity); outValue += value }
                    else -> {
                        changeStock(l.itemId, wid, l.quantity)
                        if (l.quantity > 0) inValue += value else outValue += value
                    }
                }
            }
            if (type != "transfer" && inValue + outValue > 0) {
                val title = when (type) { "receipt" -> "توريد مخزني"; "issue" -> "صرف مخزني"; else -> "تسوية مخزنية" }
                val eid = db.journal().insertEntry(JournalEntry(date = date, description = "$title #$mid" + if (remarks.isNotBlank()) " — $remarks" else "", refType = "stock_move", refId = mid))
                val inv = account("1300", "المخزون", "asset")
                if (type == "receipt") {
                    line(eid, inv, inValue, 0); line(eid, account("3300", "توريد مخزني (مقابل)", "equity"), 0, inValue)
                } else if (type == "issue") {
                    line(eid, account("5400", "صرف مخزني", "expense"), outValue, 0); line(eid, inv, 0, outValue)
                } else {
                    val diff = account("5200", "فروقات الجرد والتسويات", "expense")
                    line(eid, inv, inValue, 0); line(eid, diff, 0, inValue)
                    line(eid, diff, outValue, 0); line(eid, inv, 0, outValue)
                }
            }
            db.log().insert(OperationLog(action = "stock_$type", details = "#$mid"))
            mid
        }
    }

    suspend fun adjustStock(itemId: Long, warehouseId: Long, counted: Long) {
        require(counted >= 0) { "الكمية لا يمكن أن تكون سالبة" }
        val current = db.stock().get(itemId, warehouseId)?.quantity ?: 0L
        val diff = counted - current
        if (diff != 0L) postStockMove("adjust", warehouseId, null, listOf(MoveLineIn(itemId, diff)), "جرد")
    }

    suspend fun transferStock(itemId: Long, from: Long, to: Long, qty: Long) {
        postStockMove("transfer", from, to, listOf(MoveLineIn(itemId, qty)), "")
    }

    // ───────────── المصروفات ─────────────

    suspend fun postExpense(category: String, amount: Long, note: String) {
        require(amount > 0) { "المبلغ يجب أن يكون أكبر من صفر" }
        require(category.isNotBlank()) { "اكتب بند المصروف" }
        db.withTransaction {
            val now = System.currentTimeMillis()
            checkOpen(now)
            val name = category.trim()
            val accId = db.accounts().findByNameAndType(name, "expense")?.id
                ?: db.accounts().insert(Account(code = ((db.accounts().maxExpenseCode() ?: 6000) + 1).toString(), name = name, type = "expense"))
            val cash = account("1100", "الصندوق", "asset")
            val eid = db.journal().insertEntry(
                JournalEntry(date = now, description = "مصروف: $name" + if (note.isNotBlank()) " — $note" else "", refType = "expense", refId = 0)
            )
            line(eid, accId, amount, 0)
            line(eid, cash, 0, amount)
            db.log().insert(OperationLog(action = "expense", details = "$name: $amount"))
        }
    }
}
