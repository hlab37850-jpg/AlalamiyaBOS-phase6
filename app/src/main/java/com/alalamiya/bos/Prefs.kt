package com.alalamiya.bos

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

object Prefs {
    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences("bos_prefs", Context.MODE_PRIVATE)

    fun storeName(ctx: Context) = sp(ctx).getString("store_name", "") ?: ""
    fun storePhone(ctx: Context) = sp(ctx).getString("store_phone", "") ?: ""
    fun storeAddress(ctx: Context) = sp(ctx).getString("store_address", "") ?: ""
    fun setStore(ctx: Context, name: String, phone: String, address: String) {
        sp(ctx).edit().putString("store_name", name).putString("store_phone", phone).putString("store_address", address).apply()
    }
    fun header(ctx: Context): String =
        listOf(storeName(ctx), storePhone(ctx), storeAddress(ctx)).filter { it.isNotBlank() }.joinToString(" — ")

    fun autoBackup(ctx: Context) = sp(ctx).getBoolean("auto_backup", true)
    fun setAutoBackup(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean("auto_backup", on).apply() }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun lockEnabled(ctx: Context) = sp(ctx).contains("pin_hash")

    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sp(ctx).edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun checkPin(ctx: Context, pin: String): Boolean {
        val salt = sp(ctx).getString("pin_salt", null) ?: return false
        return hash(salt, pin) == sp(ctx).getString("pin_hash", null)
    }

    fun biometric(ctx: Context) = sp(ctx).getBoolean("biometric", false)
    fun setBiometric(ctx: Context, on: Boolean) { sp(ctx).edit().putBoolean("biometric", on).apply() }

    fun clearPin(ctx: Context) { sp(ctx).edit().remove("pin_hash").remove("pin_salt").remove("biometric").apply() }
}
