package com.alalamiya.bos

import androidx.room.withTransaction
import com.alalamiya.bos.data.Account
import com.alalamiya.bos.data.BosDatabase
import com.alalamiya.bos.data.Invoice
import com.alalamiya.bos.data.InvoiceLine
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
 */
class Ledger(private val db: BosDatabase) {

    companion object {
        /** الكمية والسعر مخزّنان ×1000، فالناتج يُقسم على 1000 مع تقريب. */
        fun lineTotal(q: Long, p: Long): Long =
            BigInteger.valueOf(q).multiply(BigInteger.valueOf(p))
                .add(BigInteger.valueOf(500)).divide(BigInteger.valueOf(1000)).toLong()

        val INVOICE_TYPES = setOf("sale", "purchase", "sale_return", "purchase_return")
    }

    private suspend fun account(code: String, name: String, type: String): Long {
        db.accounts().findByCode(code)?.let { return it.id }
        return db.accounts().insert(Account(code = code, name = name, type = type))
    }

    private suspend fun line(entryId: Long, accountId: Long, debit: Long, credit: Long) {
        if (debit + credit > 0) db.journal().insertLine(JournalLine(entryId = entryId, accountId = accountId, debit = debit, credit = credit))
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
     * فاتورة بيع / شراء / مرتجع بيع / مرتجع شراء. paid = المدفوع نقدًا (أو المسترد في المرتجعات)،
     * والباقي يُسجَّل على رصيد الطرف. المخزون يتحدث، والقيد المحاسبي يُنشأ تلقائيًا.
     */
    suspend fun postInvoice(type: String, partyId: Long?, lines: List<InvoiceLineInput>, paid: Long, note: String): Long {
        require(type in INVOICE_TYPES) { "نوع فاتورة غير معروف" }
        require(lines.isNotEmpty()) { "الفاتورة بلا أصناف" }
        require(lines.all { it.quantity > 0 && it.price >= 0 }) { "كمية أو سعر غير صالح" }
        val total = lines.sumOf { lineTotal(it.quantity, it.price) }
        require(paid in 0L..total) { "المدفوع يجب أن يكون بين صفر والإجمالي" }
        require(partyId != null || paid == total) { "الفاتورة الآجلة تحتاج اختيار عميل" }

        return db.withTransaction {
            val party = partyId?.let { db.customers().findById(it) ?: error("الطرف غير موجود") }
            val now = System.currentTimeMillis()
            val invId = db.invoices().insert(Invoice(type = type, customerId = partyId, date = now, total = total, paid = paid, note = note))

            val stockSign = if (type == "sale" || type == "purchase_return") -1 else 1
            var cost = 0L
            for (l in lines) {
                val item = db.items().findById(l.itemId) ?: error("صنف غير موجود")
                db.invoices().insertLine(InvoiceLine(invoiceId = invId, itemId = l.itemId, quantity = l.quantity, price = l.price))
                cost += lineTotal(l.quantity, item.costPrice)
                val newQty = item.quantity + stockSign * l.quantity
                db.items().update(
                    if (type == "purchase") item.copy(quantity = newQty, costPrice = l.price) else item.copy(quantity = newQty)
                )
            }

            val cash = account("1100", "الصندوق", "asset")
            val recv = account("1200", "العملاء", "asset")
            val inv = account("1300", "المخزون", "asset")
            val pay = account("2100", "الموردون", "liability")
            val sales = account("4100", "المبيعات", "income")
            val salesRet = account("4200", "مردودات المبيعات", "income")
            val cogs = account("5100", "تكلفة البضاعة المباعة", "expense")

            val label = when (type) {
                "sale" -> "فاتورة بيع"; "purchase" -> "فاتورة شراء"
                "sale_return" -> "مرتجع بيع"; else -> "مرتجع شراء"
            }
            val eid = db.journal().insertEntry(
                JournalEntry(date = now, description = "$label #$invId" + (party?.let { " - ${it.name}" } ?: ""), refType = "invoice", refId = invId)
            )
            val rest = total - paid
            when (type) {
                "sale" -> {
                    line(eid, cash, paid, 0); line(eid, recv, rest, 0); line(eid, sales, 0, total)
                    line(eid, cogs, cost, 0); line(eid, inv, 0, cost)
                }
                "sale_return" -> {
                    line(eid, salesRet, total, 0); line(eid, cash, 0, paid); line(eid, recv, 0, rest)
                    line(eid, inv, cost, 0); line(eid, cogs, 0, cost)
                }
                "purchase" -> {
                    line(eid, inv, total, 0); line(eid, cash, 0, paid); line(eid, pay, 0, rest)
                }
                else -> { // purchase_return
                    line(eid, cash, paid, 0); line(eid, pay, rest, 0); line(eid, inv, 0, total)
                }
            }

            if (party != null && rest != 0L) {
                val delta = when (type) {
                    "sale", "purchase_return" -> rest
                    else -> -rest
                }
                db.customers().update(party.copy(balance = party.balance + delta, lastContactAt = now))
            }
            db.log().insert(OperationLog(action = "invoice_$type", details = "#$invId total=$total paid=$paid"))
            invId
        }
    }

    /** تسوية جرد: تضبط كمية الصنف على الكمية الفعلية، وتسجّل قيد فروقات الجرد بسعر التكلفة. */
    suspend fun adjustStock(itemId: Long, counted: Long) {
        require(counted >= 0) { "الكمية لا يمكن أن تكون سالبة" }
        db.withTransaction {
            val item = db.items().findById(itemId) ?: error("الصنف غير موجود")
            val diff = counted - item.quantity
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
                db.items().update(item.copy(quantity = counted))
                db.log().insert(OperationLog(action = "stock_count", details = "${item.name}: ${item.quantity} -> $counted"))
            }
        }
    }

    /** إلغاء فاتورة: يُعكس أثرها على المخزون والرصيد، ويُنشأ قيد عكسي، وتبقى الفاتورة ظاهرة بحالة «ملغاة». */
    suspend fun cancelInvoice(id: Long) {
        db.withTransaction {
            val inv = db.invoices().findById(id) ?: error("الفاتورة غير موجودة")
            if (inv.cancelled) error("الفاتورة ملغاة مسبقًا")
            val now = System.currentTimeMillis()
            val stockSign = if (inv.type == "sale" || inv.type == "purchase_return") 1 else -1
            for (l in db.invoices().lines(id)) {
                val item = db.items().findById(l.itemId) ?: continue
                db.items().update(item.copy(quantity = item.quantity + stockSign * l.quantity))
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
}
