package com.alalamiya.bos

import android.app.Application
import com.alalamiya.bos.data.BosDatabase
import java.io.File

class BosApp : Application() {
    val db: BosDatabase by lazy { BosDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        applyPendingRestore()
        Background.createChannel(this)
        Background.scheduleAll(this)
    }

    /** إذا طُلب التراجع عن استيراد، تُستبدل قاعدة البيانات بالنسخة المحفوظة قبل فتحها. */
    private fun applyPendingRestore() {
        val pending = File(filesDir, "pending_restore.db")
        if (!pending.exists()) return
        val target = getDatabasePath("bos.db")
        target.parentFile?.mkdirs()
        pending.copyTo(target, overwrite = true)
        File(target.path + "-wal").delete()
        File(target.path + "-shm").delete()
        pending.delete()
    }
}
