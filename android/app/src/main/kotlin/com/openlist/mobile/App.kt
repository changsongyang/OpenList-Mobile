package com.openlist.mobile

import android.app.Application
import android.util.Log
import com.openlist.mobile.model.openlist.OpenList
import com.openlist.mobile.utils.ToastUtils.longToast
import io.flutter.app.FlutterApplication

val app by lazy { App.app }

class App : FlutterApplication() {
    companion object {
        lateinit var app: Application
        private const val TAG = "App"
    }


    override fun onCreate() {
        super.onCreate()

        app = this
        
        // 初始化Android进程保护
        try {
            com.openlist.mobile.utils.AndroidProcessManager.registerProcessProtection()
            Log.d(TAG, "Android process protection initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize process protection", e)
        }
        
        // 设置全局异常处理器来捕获未处理的异常
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            
            // 尝试优雅关闭OpenList以保护数据完整性
            try {
                if (OpenListService.isRunning) {
                    Log.w(TAG, "App crashing, attempting graceful OpenList shutdown")
                    OpenList.shutdown()
                    Log.d(TAG, "Emergency OpenList shutdown completed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to emergency shutdown OpenList", e)
            }
            
            // 如果是 JNI 相关的错误，记录详细信息
            if (throwable.message?.contains("JNI") == true || 
                throwable.message?.contains("native") == true ||
                throwable is UnsatisfiedLinkError) {
                Log.e(TAG, "Native/JNI related crash detected")
            }
            
            // 调用默认的异常处理器
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        Log.d(TAG, "App terminating")
        
        // 确保在应用终止时正确关闭OpenList
        try {
            if (OpenListService.isRunning) {
                Log.d(TAG, "App terminate: shutting down OpenList")
                OpenList.shutdown()
                Log.d(TAG, "App terminate: OpenList shutdown completed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to shutdown OpenList on app terminate", e)
        }
    }
}