package com.openlist.mobile.utils

import android.util.Log
import com.openlist.mobile.config.AppConfig
import java.io.File

/**
 * 数据库完整性检查和修复工具
 * 解决SQLite WAL文件不合并到主数据库的问题
 */
object DatabaseIntegrityHelper {
    private const val TAG = "DatabaseIntegrityHelper"
    
    /**
     * 检查数据库WAL文件状态
     */
    fun checkDatabaseWalFiles(): WalStatus {
        try {
            val dataDir = AppConfig.dataDir
            val dbFile = File(dataDir, "data.db")
            val walFile = File(dataDir, "data.db-wal")
            val shmFile = File(dataDir, "data.db-shm")
            
            val dbExists = dbFile.exists()
            val walExists = walFile.exists()
            val shmExists = shmFile.exists()
            
            val walSize = if (walExists) walFile.length() else 0L
            val shmSize = if (shmExists) shmFile.length() else 0L
            
            Log.d(TAG, "Database status check:")
            Log.d(TAG, "  data.db exists: $dbExists, size: ${if (dbExists) dbFile.length() else 0}")
            Log.d(TAG, "  data.db-wal exists: $walExists, size: $walSize")
            Log.d(TAG, "  data.db-shm exists: $shmExists, size: $shmSize")
            
            return WalStatus(
                dbExists = dbExists,
                walExists = walExists,
                shmExists = shmExists,
                walSize = walSize,
                shmSize = shmSize,
                dbPath = dbFile.absolutePath,
                walPath = walFile.absolutePath,
                shmPath = shmFile.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check database WAL files", e)
            return WalStatus()
        }
    }
    
    /**
     * 记录OpenList启动前的数据库状态
     */
    fun logDatabaseStatusBeforeStart() {
        Log.d(TAG, "=== Database status before OpenList start ===")
        val status = checkDatabaseWalFiles()
        
        if (status.walExists && status.walSize > 0) {
            Log.w(TAG, "WARNING: WAL file exists with size ${status.walSize} bytes")
            Log.w(TAG, "This indicates previous improper shutdown")
        }
        
        if (status.shmExists && status.shmSize > 0) {
            Log.w(TAG, "WARNING: SHM file exists with size ${status.shmSize} bytes")
        }
    }
    
    /**
     * 记录OpenList关闭后的数据库状态
     */
    fun logDatabaseStatusAfterShutdown() {
        Log.d(TAG, "=== Database status after OpenList shutdown ===")
        
        // 等待一段时间让数据库完全关闭
        try {
            Thread.sleep(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        
        val status = checkDatabaseWalFiles()
        
        if (status.walExists && status.walSize > 0) {
            Log.e(TAG, "ERROR: WAL file still exists after shutdown with size ${status.walSize}")
            Log.e(TAG, "This indicates improper database closure!")
        } else {
            Log.d(TAG, "SUCCESS: WAL file properly merged or removed")
        }
        
        if (status.shmExists) {
            Log.w(TAG, "WARNING: SHM file still exists after shutdown")
        }
    }
    
    /**
     * 数据库WAL状态信息
     */
    data class WalStatus(
        val dbExists: Boolean = false,
        val walExists: Boolean = false,
        val shmExists: Boolean = false,
        val walSize: Long = 0L,
        val shmSize: Long = 0L,
        val dbPath: String = "",
        val walPath: String = "",
        val shmPath: String = ""
    ) {
        val hasWalIssue: Boolean
            get() = walExists && walSize > 0
            
        val hasShmIssue: Boolean
            get() = shmExists && shmSize > 0
    }
}
