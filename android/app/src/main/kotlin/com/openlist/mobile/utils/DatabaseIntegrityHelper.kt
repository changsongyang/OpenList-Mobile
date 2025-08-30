package com.openlist.mobile.utils

import android.util.Log
import com.openlist.mobile.config.AppConfig
import kotlinx.coroutines.delay
import java.io.File

/**
 * 数据库完整性检查和修复工具
 * 解决SQLite WAL文件不合并到主数据库的问题 - Android专用
 */
object DatabaseIntegrityHelper {
    private const val TAG = "DatabaseIntegrityHelper"
    
    /**
     * Android特有的数据库关闭后处理
     * 强制等待并验证WAL文件状态
     */
    suspend fun performPostShutdownWalCheck() {
        Log.d(TAG, "Performing Android-specific post-shutdown WAL check...")
        
        try {
            // 等待更长时间让系统完成I/O操作
            delay(3000)
            
            val status = checkDatabaseWalFiles()
            
            if (status.hasWalIssue) {
                Log.w(TAG, "WAL file still exists after shutdown, performing additional checks...")
                
                // 再等待一段时间
                delay(2000)
                
                val secondCheck = checkDatabaseWalFiles()
                if (secondCheck.hasWalIssue) {
                    Log.e(TAG, "PERSISTENT WAL ISSUE: WAL file size=${secondCheck.walSize}")
                    
                    // 尝试强制触发文件系统同步
                    triggerFilesystemSync()
                } else {
                    Log.d(TAG, "WAL file resolved after additional wait")
                }
            } else {
                Log.d(TAG, "WAL file properly handled after shutdown")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during post-shutdown WAL check", e)
        }
    }
    
    /**
     * 尝试强制文件系统同步（Android特有）
     */
    private fun triggerFilesystemSync() {
        try {
            Log.d(TAG, "Attempting to trigger filesystem sync...")
            
            val dataDir = File(AppConfig.dataDir)
            if (dataDir.exists()) {
                // 尝试访问目录以触发文件系统操作
                dataDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("data.db")) {
                        Log.d(TAG, "Checking file: ${file.name}, size: ${file.length()}, lastModified: ${file.lastModified()}")
                    }
                }
            }
            
            // 强制垃圾回收，可能有助于释放文件句柄
            System.gc()
            
            Log.d(TAG, "Filesystem sync attempt completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger filesystem sync", e)
        }
    }
    
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
            val dbLastModified = if (dbExists) dbFile.lastModified() else 0L
            
            Log.d(TAG, "Database status check:")
            Log.d(TAG, "  data.db exists: $dbExists, size: ${if (dbExists) dbFile.length() else 0}, lastModified: $dbLastModified")
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
                shmPath = shmFile.absolutePath,
                dbLastModified = dbLastModified
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
            
            // 记录WAL文件的年龄
            val walFile = File(status.walPath)
            if (walFile.exists()) {
                val ageMs = System.currentTimeMillis() - walFile.lastModified()
                Log.w(TAG, "WAL file age: ${ageMs / 1000} seconds")
            }
        }
        
        if (status.shmExists && status.shmSize > 0) {
            Log.w(TAG, "WARNING: SHM file exists with size ${status.shmSize} bytes")
        }
    }
    
    /**
     * 记录OpenList关闭后的数据库状态（异步版本）
     */
    suspend fun logDatabaseStatusAfterShutdown() {
        Log.d(TAG, "=== Database status after OpenList shutdown ===")
        
        // 执行Android特有的后处理检查
        performPostShutdownWalCheck()
        
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
        
        // 检查主数据库文件是否被更新
        val currentTime = System.currentTimeMillis()
        val timeSinceDbUpdate = currentTime - status.dbLastModified
        if (status.dbExists && timeSinceDbUpdate < 30000) { // 30秒内
            Log.d(TAG, "SUCCESS: Main database file was recently updated")
        } else if (status.dbExists) {
            Log.w(TAG, "WARNING: Main database file was not recently updated (${timeSinceDbUpdate / 1000}s ago)")
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
        val shmPath: String = "",
        val dbLastModified: Long = 0L
    ) {
        val hasWalIssue: Boolean
            get() = walExists && walSize > 0
            
        val hasShmIssue: Boolean
            get() = shmExists && shmSize > 0
            
        val isDbRecentlyUpdated: Boolean
            get() = dbExists && (System.currentTimeMillis() - dbLastModified) < 60000 // 1分钟内
    }
}
