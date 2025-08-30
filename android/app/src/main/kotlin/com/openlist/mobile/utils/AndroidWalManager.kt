package com.openlist.mobile.utils

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Android端的数据库WAL文件管理工具
 * 解决Android平台特有的WAL文件不合并问题
 */
object AndroidWalManager {
    private const val TAG = "AndroidWalManager"
    
    /**
     * 强制执行WAL文件合并
     * 在OpenList关闭后调用，确保WAL文件被正确合并到主数据库
     */
    fun forceWalCheckpoint(dbPath: String): Boolean {
        return try {
            val dbFile = File(dbPath)
            if (!dbFile.exists()) {
                Log.w(TAG, "Database file does not exist: $dbPath")
                return false
            }
            
            Log.d(TAG, "Attempting to force WAL checkpoint for: $dbPath")
            
            // 使用Android SQLite API直接操作数据库
            val database = SQLiteDatabase.openDatabase(
                dbPath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            )
            
            try {
                // 执行WAL checkpoint操作
                Log.d(TAG, "Executing PRAGMA wal_checkpoint(TRUNCATE)...")
                database.execSQL("PRAGMA wal_checkpoint(TRUNCATE);")
                Log.d(TAG, "WAL checkpoint completed successfully")
                
                // 额外的清理操作
                database.execSQL("PRAGMA vacuum;")
                Log.d(TAG, "Database vacuum completed")
                
                true
            } finally {
                database.close()
                Log.d(TAG, "Database connection closed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force WAL checkpoint", e)
            false
        }
    }
    
    /**
     * 检查WAL文件状态
     */
    fun checkWalFileStatus(dbPath: String): WalFileStatus {
        val dbFile = File(dbPath)
        val walFile = File("$dbPath-wal")
        val shmFile = File("$dbPath-shm")
        
        return WalFileStatus(
            dbExists = dbFile.exists(),
            dbSize = if (dbFile.exists()) dbFile.length() else 0,
            walExists = walFile.exists(),
            walSize = if (walFile.exists()) walFile.length() else 0,
            shmExists = shmFile.exists(),
            shmSize = if (shmFile.exists()) shmFile.length() else 0
        )
    }
    
    /**
     * 清理残留的WAL和SHM文件
     * 仅在确认主数据库完整的情况下使用
     */
    fun cleanupWalFiles(dbPath: String, force: Boolean = false): Boolean {
        return try {
            val walFile = File("$dbPath-wal")
            val shmFile = File("$dbPath-shm")
            
            var cleaned = false
            
            if (walFile.exists()) {
                if (force || walFile.length() == 0L) {
                    val deleted = walFile.delete()
                    Log.d(TAG, "WAL file deletion: $deleted")
                    cleaned = cleaned || deleted
                } else {
                    Log.w(TAG, "WAL file has content (${walFile.length()} bytes), not deleting without force")
                }
            }
            
            if (shmFile.exists()) {
                val deleted = shmFile.delete()
                Log.d(TAG, "SHM file deletion: $deleted")
                cleaned = cleaned || deleted
            }
            
            cleaned
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup WAL files", e)
            false
        }
    }
}

/**
 * WAL文件状态数据类
 */
data class WalFileStatus(
    val dbExists: Boolean,
    val dbSize: Long,
    val walExists: Boolean,
    val walSize: Long,
    val shmExists: Boolean,
    val shmSize: Long
) {
    fun isWalActive(): Boolean = walExists && walSize > 0
    
    fun logStatus() {
        Log.d("WalFileStatus", """
            Database Status:
            - Main DB: ${if (dbExists) "exists ($dbSize bytes)" else "missing"}
            - WAL file: ${if (walExists) "exists ($walSize bytes)" else "missing"}
            - SHM file: ${if (shmExists) "exists ($shmSize bytes)" else "missing"}
            - WAL active: ${isWalActive()}
        """.trimIndent())
    }
}
