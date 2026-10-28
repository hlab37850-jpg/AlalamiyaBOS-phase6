package com.alalamiya.bos.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Customer::class, Item::class, Account::class, JournalEntry::class,
        JournalLine::class, Invoice::class, InvoiceLine::class, Voucher::class,
        OperationLog::class, Warehouse::class, ItemStock::class, Currency::class,
        ItemUnit::class, Lookup::class, Tax::class, AppSetting::class, VoucherLine::class,
        JournalParty::class, StockMove::class, StockMoveLine::class
    ],
    version = 5,
    exportSchema = false
)
abstract class BosDatabase : RoomDatabase() {
    abstract fun customers(): CustomerDao
    abstract fun items(): ItemDao
    abstract fun log(): LogDao
    abstract fun accounts(): AccountDao
    abstract fun journal(): JournalDao
    abstract fun vouchers(): VoucherDao
    abstract fun invoices(): InvoiceDao
    abstract fun warehouses(): WarehouseDao
    abstract fun stock(): StockDao
    abstract fun currencies(): CurrencyDao
    abstract fun itemUnits(): ItemUnitDao
    abstract fun lookups(): LookupDao
    abstract fun taxes(): TaxDao
    abstract fun settings(): SettingsDao
    abstract fun journalParty(): JournalPartyDao
    abstract fun stockMoves(): StockMoveDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoices ADD COLUMN cancelled INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS warehouses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, isDefault INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS item_stock (itemId INTEGER NOT NULL, warehouseId INTEGER NOT NULL, quantity INTEGER NOT NULL, PRIMARY KEY(itemId, warehouseId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS currencies (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, code TEXT NOT NULL, name TEXT NOT NULL, rate INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_currencies_code ON currencies (code)")
                db.execSQL("ALTER TABLE invoices ADD COLUMN discount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE invoices ADD COLUMN warehouseId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE invoices ADD COLUMN currency TEXT NOT NULL DEFAULT 'YER'")
                db.execSQL("ALTER TABLE invoices ADD COLUMN rate INTEGER NOT NULL DEFAULT 1000")
                seed(db)
                db.execSQL("INSERT INTO item_stock (itemId, warehouseId, quantity) SELECT id, 1, quantity FROM items")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoices ADD COLUMN tax INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE invoices ADD COLUMN fees INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE customers ADD COLUMN kind INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE customers ADD COLUMN debitLimit INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE customers ADD COLUMN creditLimit INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE customers ADD COLUMN vatNo TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE customers ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE items ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE invoice_lines ADD COLUMN factor INTEGER NOT NULL DEFAULT 1000")
                db.execSQL("ALTER TABLE invoice_lines ADD COLUMN unitName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE vouchers ADD COLUMN fundCode TEXT NOT NULL DEFAULT '1100'")
                db.execSQL("CREATE TABLE IF NOT EXISTS item_units (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER NOT NULL, name TEXT NOT NULL, factor INTEGER NOT NULL, price INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_item_units_itemId ON item_units (itemId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS lookups (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, kind INTEGER NOT NULL, name TEXT NOT NULL, code TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lookups_kind ON lookups (kind)")
                db.execSQL("CREATE TABLE IF NOT EXISTS taxes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, percent INTEGER NOT NULL, isDefault INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE IF NOT EXISTS app_settings (skey TEXT NOT NULL, sval TEXT NOT NULL, PRIMARY KEY(skey))")
                db.execSQL("CREATE TABLE IF NOT EXISTS voucher_lines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, voucherId INTEGER NOT NULL, partyId INTEGER, accountId INTEGER, amount INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_voucher_lines_voucherId ON voucher_lines (voucherId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS journal_party (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, entryId INTEGER NOT NULL, partyId INTEGER NOT NULL, delta INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_party_entryId ON journal_party (entryId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS stock_moves (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, type TEXT NOT NULL, date INTEGER NOT NULL, warehouseId INTEGER NOT NULL, toWarehouseId INTEGER, remarks TEXT NOT NULL DEFAULT '')")
                db.execSQL("CREATE TABLE IF NOT EXISTS stock_move_lines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, moveId INTEGER NOT NULL, itemId INTEGER NOT NULL, quantity INTEGER NOT NULL, cost INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_move_lines_moveId ON stock_move_lines (moveId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_move_lines_itemId ON stock_move_lines (itemId)")
            }
        }

        private fun seed(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT INTO warehouses (id, name, isDefault) VALUES (1, 'المستودع الرئيسي', 1)")
            db.execSQL("INSERT INTO currencies (code, name, rate) VALUES ('YER', 'ريال يمني', 1000)")
        }

        @Volatile private var inst: BosDatabase? = null
        fun get(ctx: Context): BosDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, BosDatabase::class.java, "bos.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) { seed(db) }
                })
                .build().also { inst = it }
        }
    }
}
