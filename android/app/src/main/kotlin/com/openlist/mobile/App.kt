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
                    Log.w(TAG, "App crashing, sending emergency shutdown signal")
                    // 在主线程发送关闭信号，确保信号正确传递
                    OpenList.shutdown()
                    // 给Go进程一点时间完成关闭
                    Thread.sleep(2000)
                    Log.d(TAG, "Emergency shutdown signal sent")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send emergency shutdown signal", e)
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
                Log.d(TAG, "App terminate: sending shutdown signal")
                // 在主线程发送关闭信号
                OpenList.shutdown()
                Thread.sleep(2000) // 给Go进程时间完成关闭
                Log.d(TAG, "App terminate: shutdown signal sent")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send shutdown signal on app terminate", e)
        }
    }
}