package com.alalamiya.bos.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY name")
    fun observeAll(): Flow<List<Customer>>

    @Query("SELECT COUNT(*) FROM customers")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(balance), 0) FROM customers")
    fun observeTotalBalance(): Flow<Long>

    @Insert suspend fun insert(c: Customer): Long
    @Query("SELECT * FROM customers WHERE id = :id") suspend fun findById(id: Long): Customer?
    @Query("SELECT * FROM customers WHERE name = :n LIMIT 1") suspend fun findByName(n: String): Customer?
    @Query("SELECT * FROM customers WHERE id = :id") fun observeById(id: Long): Flow<Customer?>
    @Query("SELECT * FROM customers WHERE dueDate IS NOT NULL AND balance > 0 ORDER BY dueDate") fun observeWithDue(): Flow<List<Customer>>
    @Query("SELECT * FROM customers WHERE dueDate IS NOT NULL AND dueDate <= :until AND balance > 0 ORDER BY dueDate") suspend fun dueCustomers(until: Long): List<Customer>
    @Update suspend fun update(c: Customer)
    @Delete suspend fun delete(c: Customer)
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY name")
    fun observeAll(): Flow<List<Item>>

    @Query("SELECT COUNT(*) FROM items")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM items WHERE minStock > 0 AND quantity <= minStock")
    fun observeLowStockCount(): Flow<Int>

    @Insert suspend fun insert(i: Item): Long
    @Query("SELECT * FROM items WHERE name = :n LIMIT 1") suspend fun findByName(n: String): Item?
    @Query("SELECT * FROM items WHERE id = :id") suspend fun findById(id: Long): Item?
    @Query("SELECT * FROM items WHERE barcode = :code AND barcode <> '' LIMIT 1") suspend fun findByBarcode(code: String): Item?
    @Update suspend fun update(i: Item)
    @Delete suspend fun delete(i: Item)
}

@Dao
interface LogDao {
    @Insert suspend fun insert(l: OperationLog)

    @Query("SELECT * FROM operation_log ORDER BY ts DESC LIMIT 200")
    fun observeRecent(): Flow<List<OperationLog>>
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE code = :code LIMIT 1")
    suspend fun findByCode(code: String): Account?

    @Query("SELECT * FROM accounts WHERE name = :n AND type = :t LIMIT 1")
    suspend fun findByNameAndType(n: String, t: String): Account?

    @Query("SELECT MAX(CAST(code AS INTEGER)) FROM accounts WHERE code LIKE '6%'")
    suspend fun maxExpenseCode(): Int?

    @Query("SELECT * FROM accounts WHERE code LIKE '6%' ORDER BY name")
    fun observeExpenseCategories(): Flow<List<Account>>

    @Insert suspend fun insert(a: Account): Long

    @Query("SELECT a.code AS code, a.name AS name, COALESCE(SUM(l.debit),0) AS debit, COALESCE(SUM(l.credit),0) AS credit FROM accounts a LEFT JOIN journal_lines l ON l.accountId = a.id GROUP BY a.id ORDER BY a.code")
    fun observeBalances(): Flow<List<AccountBalance>>
}

@Dao
interface JournalDao {
    @Insert suspend fun insertEntry(e: JournalEntry): Long
    @Insert suspend fun insertLine(l: JournalLine): Long
    @Query("SELECT * FROM journal_entries WHERE refType = :t AND refId = :id LIMIT 1") suspend fun entryFor(t: String, id: Long): JournalEntry?
    @Query("SELECT * FROM journal_lines WHERE entryId = :eid") suspend fun linesFor(eid: Long): List<JournalLine>
    @Query("SELECT a.type AS type, a.code AS code, a.name AS name, COALESCE(SUM(l.debit),0) AS debit, COALESCE(SUM(l.credit),0) AS credit FROM journal_lines l JOIN journal_entries e ON e.id = l.entryId JOIN accounts a ON a.id = l.accountId WHERE e.date >= :since AND a.type IN ('income','expense') GROUP BY a.id ORDER BY a.code")
    fun observePnl(since: Long): Flow<List<TypedBalance>>

    @Query("SELECT e.id AS id, e.date AS date, e.description AS description, COALESCE(SUM(l.debit),0) AS amount FROM journal_entries e JOIN journal_lines l ON l.entryId = e.id WHERE e.refType = 'expense' GROUP BY e.id ORDER BY e.date DESC LIMIT 200")
    fun observeExpenses(): Flow<List<ExpenseRow>>
}

@Dao
interface VoucherDao {
    @Insert suspend fun insert(v: Voucher): Long
    @Query("SELECT * FROM vouchers WHERE customerId = :cid") suspend fun forCustomer(cid: Long): List<Voucher>

    @Query("SELECT * FROM vouchers ORDER BY date DESC LIMIT 200")
    fun observeRecent(): Flow<List<Voucher>>
}

@Dao
interface InvoiceDao {
    @Insert suspend fun insert(i: Invoice): Long
    @Insert suspend fun insertLine(l: InvoiceLine): Long
    @Query("SELECT * FROM invoice_lines WHERE invoiceId = :id") suspend fun lines(id: Long): List<InvoiceLine>
    @Query("SELECT * FROM invoices WHERE id = :id") suspend fun findById(id: Long): Invoice?
    @Query("UPDATE invoices SET cancelled = 1 WHERE id = :id") suspend fun markCancelled(id: Long)
    @Query("SELECT * FROM invoices WHERE customerId = :cid") suspend fun forCustomer(cid: Long): List<Invoice>
    @Query("SELECT i.date AS date, i.type AS type, i.id AS invoiceId, l.quantity AS quantity, l.price AS price FROM invoice_lines l JOIN invoices i ON i.id = l.invoiceId WHERE l.itemId = :itemId AND i.cancelled = 0 ORDER BY i.date DESC")
    suspend fun movesFor(itemId: Long): List<ItemMove>

    @Query("SELECT * FROM invoices ORDER BY date DESC LIMIT 200")
    fun observeRecent(): Flow<List<Invoice>>
}

data class AccountBalance(val code: String, val name: String, val debit: Long, val credit: Long)

data class ItemMove(val date: Long, val type: String, val invoiceId: Long, val quantity: Long, val price: Long)
data class TypedBalance(val type: String, val code: String, val name: String, val debit: Long, val credit: Long)

data class ExpenseRow(val id: Long, val date: Long, val description: String, val amount: Long)

@Dao
interface WarehouseDao {
    @Query("SELECT * FROM warehouses ORDER BY isDefault DESC, name")
    fun observeAll(): Flow<List<Warehouse>>

    @Query("SELECT id FROM warehouses WHERE isDefault = 1 LIMIT 1")
    suspend fun defaultId(): Long?

    @Insert suspend fun insert(w: Warehouse): Long
    @Update suspend fun update(w: Warehouse)
}

@Dao
interface StockDao {
    @Query("SELECT * FROM item_stock WHERE itemId = :itemId AND warehouseId = :wid")
    suspend fun get(itemId: Long, wid: Long): ItemStock?

    @Query("SELECT * FROM item_stock WHERE itemId = :itemId")
    fun observeForItem(itemId: Long): Flow<List<ItemStock>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: ItemStock)
}

@Dao
interface CurrencyDao {
    @Query("SELECT * FROM currencies ORDER BY id")
    fun observeAll(): Flow<List<Currency>>

    @Query("SELECT * FROM currencies WHERE code = :code LIMIT 1")
    suspend fun findByCode(code: String): Currency?

    @Insert suspend fun insert(c: Currency): Long
    @Update suspend fun update(c: Currency)
}
