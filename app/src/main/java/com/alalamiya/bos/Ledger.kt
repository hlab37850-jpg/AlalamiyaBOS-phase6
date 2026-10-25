package com.alalamiya.bos

import androidx.room.withTransaction
import com.alalamiya.bos.data.Account
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Invoice
import com.alalamiya.bos.data.InvoiceLine
import com.alalamiya.bos.data.ItemStock
import com.alalamiya.bos.data.JournalEntry
import com.alalamiya.bos.data.JournalLine
import com.alalamiya.bos.data.OperationLog
import com.alalamiya.bos.data.Voucher
import java.math.BigInteger
import kotlin.math.abs

data class InvoiceLineInput(val itemId: Long, val quantity: Long, val price: Long)

/**
 * المحرك المحاسبي: كل عملية مالية تنتج قيدًا مزدوجًا متوازنًا وتحدّث الرصيد والمخزون في معاملة واحدة
 * (إما تنجح كلها أو لا يتغير شيء). الرصيد الموجب = على الطرف (مدين)، والسالب = له (دائن).
 * كل المبالغ المخزنة بالعملة الأساسية؛ الفواتير بعملة أخرى تُحوَّل عند الحفظ بسعر الصرف المسجَّل.
 */
class Ledger(private val db: BosDatabase) {

    companion object {
        private fun mulDiv(a: Long, b: Long, d: Long): Long =
            BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
                .add(BigInteger.valueOf(d / 2)).divide(BigInteger.valueOf(d)).toLong()

        /** الكمية والسعر مخزّنان ×1000، فالناتج يُقسم على 1000 مع تقريب. */
        fun lineTotal(q: Long, p: Long): Long = mulDiv(q, p, 1000)

        /** من عملة الفاتورة إلى الأساسية. */
        fun convert(x: Long, rate: Long): Long = mulDiv(x, rate, 1000)

        /** من الأساسية إلى عملة الفاتورة. */
        fun fromBase(x: Long, rate: Long): Long = mulDiv(x, 1000, rate)

        val INVOICE_TYPES = setOf("sale", "purchase", "sale_return", "purchase_return")
    }

    private suspend fun account(code: String, name: String, type: String): Long {
        db.accounts().findByCode(code)?.let { return it.id }
        return db.accounts().insert(Account(code = code, name = name, type = type))
    }

    private suspend fun line(entryId: Long, accountId: Long, debit: Long, credit: Long) {
        if (debit + credit > 0) db.journal().insertLine(JournalLine(entryId = entryId, accountId = accountId, debit = debit, credit = credit))
    }

    /** يغيّر مخزون الصنف في مستودع، ويُبقي Item.quantity مساويًا لمجموع المستودعات. */
    private suspend fun changeStock(itemId: Long, wid: Long, delta: Long, newCost: Long? = null) {
        val item = db.items().findById(itemId) ?: error("صنف غير موجود")
        val row = db.stock().get(itemId, wid)
        db.stock().upsert(ItemStock(itemId, wid, (row?.quantity ?: 0L) + delta))
        db.items().update(item.copy(quantity = item.quantity + delta, costPrice = newCost ?: item.costPrice))
    }

    /** type = receipt (قبض من الطرف) أو payment (صرف للطرف). */
    suspend fun postVoucher(type: String, customerId: Long, amount: Long, note: String) {
        require(amount > 0) { "المبلغ يجب أن يكون أكبر من صفر" }
        require(type == "receipt" || type == "payment")
        db.withTransaction {
            val c = db.customers().findById(customerId) ?: error("العميل غير موجود")
            val cash = account("1100", "الصندوق", "asset")
            val recv = account("1200", "العملاء", "asset")
            val now = System.currentTimeMillis()
            val vid = db.vouchers().insert(Voucher(type = type, customerId = customerId, amount = amount, date = now, note = note))
            val title = if (type == "receipt") "سند قبض" else "سند صرف"
            val eid = db.journal().insertEntry(JournalEntry(date = now, description = "$title - ${c.name}", refType = "voucher", refId = vid))
            val newBalance: Long
            if (type == "receipt") {
                line(eid, cash, amount, 0); line(eid, recv, 0, amount)
                newBalance = c.balance - amount
            } else {
                line(eid, recv, amount, 0); line(eid, cash, 0, amount)
                newBalance = c.balance + amount
            }
            db.customers().update(c.copy(balance = newBalance, lastContactAt = now))
            db.log().insert(OperationLog(action = "voucher_$type", details = "${c.name}: $amount"))
        }
    }

    /**
     * فاتورة بيع / شراء / مرتجع بيع / مرتجع شراء.
     * الأسعار والخصم والمدفوع بعملة الفاتورة (rate = سعر صرفها ×1000)، وتُحفظ كلها بالعملة الأساسية.
     * paid = null يعني دفع كامل الصافي. الباقي يُسجَّل على رصيد الطرف.
     */
    suspend fun postInvoice(
        type: String, partyId: Long?, lines: List<InvoiceLineInput>, paid: Long?, discount: Long,
        note: String, warehouseId: Long, currency: String, rate: Long
    ): Long {
        require(type in INVOICE_TYPES) { "نوع فاتورة غير معروف" }
        require(lines.isNotEmpty()) { "الفاتورة بلا أصناف" }
        require(lines.all { it.quantity > 0 && it.price >= 0 }) { "كمية أو سعر غير صالح" }
        require(rate > 0) { "سعر الصرف غير صالح" }
        require(discount >= 0 && (paid == null || paid >= 0)) { "قيمة غير صالحة" }

        val baseLines = lines.map { it.copy(price = convert(it.price, rate)) }
        val gross = baseLines.sumOf { lineTotal(it.quantity, it.price) }
        val disc = minOf(convert(discount, rate), gross)
        val net = gross - disc
        val paidBase = if (paid == null) net else minOf(convert(paid, rate), net)
        require(partyId != null || paidBase == net) { "الفاتورة الآجلة تحتاج اختيار عميل" }

        return db.withTransaction {
            val party = partyId?.let { db.customers().findById(it) ?: error("الطرف غير موجود") }
            val now = System.currentTimeMillis()
            val invId = db.invoices().insert(
                Invoice(
                    type = type, customerId = partyId, date = now, total = net, paid = paidBase, note = note,
                    discount = disc, warehouseId = warehouseId, currency = currency, rate = rate
                )
            )

            val stockSign = if (type == "sale" || type == "purchase_return") -1 else 1
            var cost = 0L
            for (l in baseLines) {
                val item = db.items().findById(l.itemId) ?: error("صنف غير موجود")
                db.invoices().insertLine(InvoiceLine(invoiceId = invId, itemId = l.itemId, quantity = l.quantity, price = l.price))
                cost += lineTotal(l.quantity, item.costPrice)
                changeStock(l.itemId, warehouseId, stockSign * l.quantity, if (type == "purchase") l.price else null)
            }

            val cash = account("1100", "الصندوق", "asset")
            val recv = account("1200", "العملاء", "asset")
            val inv = account("1300", "المخزون", "asset")
            val pay = account("2100", "الموردون", "liability")
            val sales = account("4100", "المبيعات", "income")
            val salesRet = account("4200", "مردودات المبيعات", "income")
            val discGiven = account("4300", "خصم مسموح به", "income")
            val discEarned = account("4400", "خصم مكتسب", "income")
            val cogs = account("5100", "تكلفة البضاعة المباعة", "expense")

            val label = when (type) {
                "sale" -> "فاتورة بيع"; "purchase" -> "فاتورة شراء"
                "sale_return" -> "مرتجع بيع"; else -> "مرتجع شراء"
            }
            val eid = db.journal().insertEntry(
                JournalEntry(date = now, description = "$label #$invId" + (party?.let { " - ${it.name}" } ?: ""), refType = "invoice", refId = invId)
            )
            val rest = net - paidBase
            when (type) {
                "sale" -> {
                    line(eid, cash, paidBase, 0); line(eid, recv, rest, 0); line(eid, discGiven, disc, 0); line(eid, sales, 0, gross)
                    line(eid, cogs, cost, 0); line(eid, inv, 0, cost)
                }
                "sale_return" -> {
                    line(eid, salesRet, gross, 0); line(eid, discGiven, 0, disc); line(eid, cash, 0, paidBase); line(eid, recv, 0, rest)
                    line(eid, inv, cost, 0); line(eid, cogs, 0, cost)
                }
                "purchase" -> {
                    line(eid, inv, gross, 0); line(eid, cash, 0, paidBase); line(eid, pay, 0, rest); line(eid, discEarned, 0, disc)
                }
                else -> { // purchase_return
                    line(eid, cash, paidBase, 0); line(eid, pay, rest, 0); line(eid, discEarned, disc, 0); line(eid, inv, 0, gross)
                }
            }

            if (party != null && rest != 0L) {
                val delta = when (type) {
                    "sale", "purchase_return" -> rest
                    else -> -rest
                }
                db.customers().update(party.copy(balance = party.balance + delta, lastContactAt = now))
            }
            db.log().insert(OperationLog(action = "invoice_$type", details = "#$invId total=$net paid=$paidBase"))
            invId
        }
    }

    /** إلغاء فاتورة: يُعكس أثرها على المخزون والرصيد، ويُنشأ قيد عكسي، وتبقى ظاهرة بحالة «ملغاة». */
    suspend fun cancelInvoice(id: Long) {
        db.withTransaction {
            val inv = db.invoices().findById(id) ?: error("الفاتورة غير موجودة")
            if (inv.cancelled) error("الفاتورة ملغاة مسبقًا")
            val now = System.currentTimeMillis()
            val stockSign = if (inv.type == "sale" || inv.type == "purchase_return") 1 else -1
            for (l in db.invoices().lines(id)) {
                if (db.items().findById(l.itemId) == null) continue
                changeStock(l.itemId, inv.warehouseId, stockSign * l.quantity)
            }
            val rest = inv.total - inv.paid
            val cid = inv.customerId
            if (cid != null && rest != 0L) {
                val p = db.customers().findById(cid)
                if (p != null) {
                    val delta = if (inv.type == "sale" || inv.type == "purchase_return") -rest else rest
                    db.customers().update(p.copy(balance = p.balance + delta))
                }
            }
            val orig = db.journal().entryFor("invoice", id)
            if (orig != null) {
                val eid = db.journal().insertEntry(JournalEntry(date = now, description = "إلغاء: ${orig.description}", refType = "invoice_cancel", refId = id))
                for (jl in db.journal().linesFor(orig.id)) line(eid, jl.accountId, jl.credit, jl.debit)
            }
            db.invoices().markCancelled(id)
            db.log().insert(OperationLog(action = "invoice_cancel", details = "#$id"))
        }
    }

    /** تسوية جرد في مستودع: تضبط الكمية على الفعلية، وتسجّل فرق الجرد بسعر التكلفة. */
    suspend fun adjustStock(itemId: Long, warehouseId: Long, counted: Long) {
        require(counted >= 0) { "الكمية لا يمكن أن تكون سالبة" }
        db.withTransaction {
            val item = db.items().findById(itemId) ?: error("الصنف غير موجود")
            val current = db.stock().get(itemId, warehouseId)?.quantity ?: 0L
            val diff = counted - current
            if (diff != 0L) {
                val value = lineTotal(abs(diff), item.costPrice)
                if (value > 0) {
                    val now = System.currentTimeMillis()
                    val inv = account("1300", "المخزون", "asset")
                    val loss = account("5200", "فروقات الجرد", "expense")
                    val eid = db.journal().insertEntry(JournalEntry(date = now, description = "جرد: ${item.name}", refType = "stock_count", refId = item.id))
                    if (diff < 0) { line(eid, loss, value, 0); line(eid, inv, 0, value) }
                    else { line(eid, inv, value, 0); line(eid, loss, 0, value) }
                }
                changeStock(itemId, warehouseId, diff)
                db.log().insert(OperationLog(action = "stock_count", details = "${item.name}: $current -> $counted"))
            }
        }
    }

    /** مناقلة مخزون بين مستودعين (لا قيد محاسبي لأن القيمة لا تتغير). */
    suspend fun transferStock(itemId: Long, from: Long, to: Long, qty: Long) {
        require(from != to) { "اختر مستودعين مختلفين" }
        require(qty > 0) { "الكمية يجب أن تكون أكبر من صفر" }
        db.withTransaction {
            val item = db.items().findById(itemId) ?: error("الصنف غير موجود")
            val available = db.stock().get(itemId, from)?.quantity ?: 0L
            require(available >= qty) { "الكمية في المستودع المصدر غير كافية" }
            changeStock(itemId, from, -qty)
            changeStock(itemId, to, qty)
            db.log().insert(OperationLog(action = "stock_transfer", details = "${item.name}: $qty"))
        }
    }

    /** مصروف نقدي من الصندوق على بند مصروفات (يُنشأ البند تلقائيًا إن لم يوجد). */
    suspend fun postExpense(category: String, amount: Long, note: String) {
        require(amount > 0) { "المبلغ يجب أن يكون أكبر من صفر" }
        require(category.isNotBlank()) { "اكتب بند المصروف" }
        db.withTransaction {
            val name = category.trim()
            val accId = db.accounts().findByNameAndType(name, "expense")?.id
                ?: db.accounts().insert(Account(code = ((db.accounts().maxExpenseCode() ?: 6000) + 1).toString(), name = name, type = "expense"))
            val cash = account("1100", "الصندوق", "asset")
            val now = System.currentTimeMillis()
            val eid = db.journal().insertEntry(
                JournalEntry(date = now, description = "مصروف: $name" + if (note.isNotBlank()) " — $note" else "", refType = "expense", refId = 0)
            )
            line(eid, accId, amount, 0)
            line(eid, cash, 0, amount)
            db.log().insert(OperationLog(action = "expense", details = "$name: $amount"))
        }
    }
}
