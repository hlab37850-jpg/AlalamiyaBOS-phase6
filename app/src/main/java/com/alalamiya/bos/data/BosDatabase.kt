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
        OperationLog::class
    ],
    version = 2,
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

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoices ADD COLUMN cancelled INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var inst: BosDatabase? = null
        fun get(ctx: Context): BosDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, BosDatabase::class.java, "bos.db")
                .addMigrations(MIGRATION_1_2)
                .build().also { inst = it }
        }
    }
}
