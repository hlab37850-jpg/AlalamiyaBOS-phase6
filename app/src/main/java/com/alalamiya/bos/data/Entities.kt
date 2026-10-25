package com.alalamiya.bos.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** كل المبالغ والكميات تُخزَّن كأعداد صحيحة بدقة 3 خانات عشرية (القيمة × 1000) لتفادي أخطاء الفاصلة العائمة. */

@Entity(tableName = "customers", indices = [Index("name"), Index("phone")])
data class Customer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val notes: String = "",
    val balance: Long = 0,            // موجب = عليه، سالب = له
    val currency: String = "YER",
    val status: String = "active",
    val dueDate: Long? = null,
    val lastContactAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "items", indices = [Index("name"), Index("category")])
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: String = "",
    val unit: String = "",
    val quantity: Long = 0,
    val minStock: Long = 0,
    val reorderLevel: Long = 0,
    val costPrice: Long = 0,
    val salePrice: Long = 0,
    val barcode: String = "",
    val notes: String = ""
)

@Entity(tableName = "accounts", indices = [Index("code", unique = true)])
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val type: String,                 // asset, liability, equity, income, expense
    val parentId: Long? = null
)

@Entity(tableName = "journal_entries", indices = [Index("date")])
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val description: String = "",
    val refType: String = "",
    val refId: Long = 0
)

@Entity(tableName = "journal_lines", indices = [Index("entryId"), Index("accountId")])
data class JournalLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: Long,
    val accountId: Long,
    val debit: Long = 0,
    val credit: Long = 0
)

@Entity(tableName = "invoices", indices = [Index("customerId"), Index("date")])
data class Invoice(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,                 // sale, purchase, sale_return, purchase_return
    val customerId: Long? = null,
    val date: Long = System.currentTimeMillis(),
    val total: Long = 0,
    val paid: Long = 0,
    val note: String = "",
    @ColumnInfo(defaultValue = "0") val cancelled: Boolean = false
)

@Entity(tableName = "invoice_lines", indices = [Index("invoiceId"), Index("itemId")])
data class InvoiceLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val invoiceId: Long,
    val itemId: Long,
    val quantity: Long,
    val price: Long
)

@Entity(tableName = "vouchers", indices = [Index("customerId"), Index("date")])
data class Voucher(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,                 // receipt, payment
    val customerId: Long? = null,
    val amount: Long,
    val date: Long = System.currentTimeMillis(),
    val note: String = ""
)

@Entity(tableName = "operation_log", indices = [Index("ts")])
data class OperationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long = System.currentTimeMillis(),
    val action: String,
    val details: String = ""
)
