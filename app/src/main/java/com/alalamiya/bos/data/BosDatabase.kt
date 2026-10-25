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
        OperationLog::class, Warehouse::class, ItemStock::class, Currency::class
    ],
    version = 4,
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

        private fun seed(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT INTO warehouses (id, name, isDefault) VALUES (1, 'المستودع الرئيسي', 1)")
            db.execSQL("INSERT INTO currencies (code, name, rate) VALUES ('YER', 'ريال يمني', 1000)")
        }

        @Volatile private var inst: BosDatabase? = null
        fun get(ctx: Context): BosDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, BosDatabase::class.java, "bos.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) { seed(db) }
                })
                .build().also { inst = it }
        }
    }
}
