package com.openlist.mobile.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.util.Log
import com.openlist.mobile.OpenListService
import com.openlist.mobile.app
import com.openlist.mobile.model.openlist.OpenList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Android进程管理器
 * 专门处理Android系统的进程生命周期和强制终止问题
 */
object AndroidProcessManager {
    private const val TAG = "AndroidProcessManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /**
     * 注册进程终止监听器
     * 在Android可能杀死进程时尝试保护数据
     */
    fun registerProcessProtection() {
        Log.d(TAG, "Registering Android process protection")
        
        // 注册低内存监听
        registerLowMemoryProtection()
        
        // 注册JVM关闭钩子（尽力而为）
        registerShutdownHook()
    }
    
    /**
     * 注册低内存保护
     */
    private fun registerLowMemoryProtection() {
        try {
            val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
            // 监控内存状态
            scope.launch {
                while (true) {
                    try {
                        val memoryInfo = ActivityManager.MemoryInfo()
                        activityManager.getMemoryInfo(memoryInfo)
                        
                        // 检查是否处于低内存状态
                        if (memoryInfo.lowMemory) {
                            Log.w(TAG, "Low memory detected, available: ${memoryInfo.availMem / 1024 / 1024}MB")
                            
                            // 如果OpenList正在运行，尝试优雅关闭
                            if (OpenListService.isRunning) {
                                Log.w(TAG, "Attempting graceful shutdown due to low memory")
                                performEmergencyShutdown("LOW_MEMORY")
                            }
                        }
                        
                        delay(5000) // 每5秒检查一次
                    } catch (e: Exception) {
                        Log.e(TAG, "Error monitoring memory", e)
                        delay(10000) // 出错时等待更长时间
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register low memory protection", e)
        }
    }
    
    /**
     * 注册JVM关闭钩子
     */
    private fun registerShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(Thread {
                Log.w(TAG, "JVM shutdown hook triggered")
                
                if (OpenListService.isRunning) {
                    Log.w(TAG, "Emergency shutdown due to JVM termination")
                    
                    try {
                        // 使用runBlocking尝试同步关闭
                        runBlocking {
                            withTimeout(5000) { // 5秒超时，因为关闭钩子时间有限
                                OpenList.shutdown()
                                Log.d(TAG, "Emergency shutdown completed")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Emergency shutdown failed", e)
                    }
                }
            })
            
            Log.d(TAG, "JVM shutdown hook registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register shutdown hook", e)
        }
    }
    
    /**
     * 执行紧急关闭
     */
    private suspend fun performEmergencyShutdown(reason: String) {
        try {
            Log.w(TAG, "Performing emergency shutdown: $reason")
            
            withTimeout(8000) { // 8秒超时
                OpenList.shutdown()
                
                // 等待数据库操作完成
                delay(1000)
                
                // 检查关闭结果
                DatabaseIntegrityHelper.logDatabaseStatusAfterShutdown()
            }
            
            Log.d(TAG, "Emergency shutdown completed: $reason")
        } catch (e: Exception) {
            Log.e(TAG, "Emergency shutdown failed: $reason", e)
        }
    }
    
    /**
     * 检查进程是否处于危险状态
     */
    fun checkProcessHealth(): ProcessHealth {
        try {
            val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            val myPid = Process.myPid()
            val runningApps = activityManager.runningAppProcesses
            
            val myProcess = runningApps?.find { it.pid == myPid }
            
            val health = ProcessHealth(
                isLowMemory = memoryInfo.lowMemory,
                availableMemoryMB = memoryInfo.availMem / 1024 / 1024,
                totalMemoryMB = memoryInfo.totalMem / 1024 / 1024,
                memoryThreshold = memoryInfo.threshold / 1024 / 1024,
                processImportance = myProcess?.importance ?: -1,
                pid = myPid
            )
            
            Log.d(TAG, "Process health: $health")
            return health
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check process health", e)
            return ProcessHealth()
        }
    }
    
    /**
     * 强制触发数据库同步
     * 在Android系统可能杀死进程前调用
     */
    suspend fun forceDatabaseSync() {
        try {
            Log.d(TAG, "Forcing database sync before potential process termination")
            
            if (OpenListService.isRunning) {
                // 快速关闭并重启，强制数据库写入
                Log.d(TAG, "Quick restart to force database sync")
                
                OpenList.shutdown()
                delay(2000) // 等待关闭完成
                
                // 检查数据库状态
                val status = DatabaseIntegrityHelper.checkDatabaseWalFiles()
                if (!status.hasWalIssue) {
                    Log.d(TAG, "Database sync successful")
                } else {
                    Log.w(TAG, "Database sync may not be complete")
                }
                
                // 重新启动
                OpenList.startup()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force database sync", e)
        }
    }
    
    /**
     * 进程健康状态
     */
    data class ProcessHealth(
        val isLowMemory: Boolean = false,
        val availableMemoryMB: Long = 0,
        val totalMemoryMB: Long = 0,
        val memoryThreshold: Long = 0,
        val processImportance: Int = -1,
        val pid: Int = -1
    ) {
        val isInDanger: Boolean
            get() = isLowMemory || processImportance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        
        val memoryUsagePercent: Double
            get() = if (totalMemoryMB > 0) {
                ((totalMemoryMB - availableMemoryMB).toDouble() / totalMemoryMB) * 100
            } else 0.0
    }
}
