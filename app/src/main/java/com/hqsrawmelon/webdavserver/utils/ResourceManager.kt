package com.hqsrawmelon.webdavserver.utils

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.*
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 资源管理优化工具
 * 处理内存泄漏、资源清理和生命周期管理
 */
object ResourceManager {
    private val activities = ConcurrentHashMap<String, WeakReference<Activity>>()
    private val coroutineScopes = ConcurrentHashMap<String, CoroutineScope>()
    private val disposables = ConcurrentHashMap<String, MutableList<() -> Unit>>()

    /**
     * 注册 Activity
     */
    fun registerActivity(
        key: String,
        activity: Activity,
    ) {
        activities[key] = WeakReference(activity)
    }

    /**
     * 注销 Activity
     */
    fun unregisterActivity(key: String) {
        activities.remove(key)
        cleanupResources(key)
    }

    /**
     * 获取 Activity（如果仍然有效）
     */
    fun getActivity(key: String): Activity? = activities[key]?.get()

    /**
     * 创建带生命周期管理的 CoroutineScope
     */
    fun createScope(key: String): CoroutineScope =
        coroutineScopes.getOrPut(key) {
            CoroutineScope(Dispatchers.Main + SupervisorJob())
        }

    /**
     * 清理指定 key 的 CoroutineScope
     */
    fun cleanupScope(key: String) {
        coroutineScopes[key]?.cancel()
        coroutineScopes.remove(key)
    }

    /**
     * 添加可清理的资源
     */
    fun addDisposable(
        key: String,
        disposable: () -> Unit,
    ) {
        disposables.getOrPut(key) { mutableListOf() }.add(disposable)
    }

    /**
     * 清理指定 key 的所有资源
     */
    fun cleanupResources(key: String) {
        // 清理可清理资源
        disposables[key]?.forEach { it.invoke() }
        disposables.remove(key)

        // 清理协程
        cleanupScope(key)
    }

    /**
     * 清理所有资源
     */
    fun cleanupAll() {
        disposables.keys.forEach { key ->
            cleanupResources(key)
        }
        activities.clear()
    }

    /**
     * 检查内存使用情况
     */
    fun checkMemoryUsage(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val availableMemory = maxMemory - usedMemory

        return MemoryInfo(
            usedMemory = usedMemory,
            maxMemory = maxMemory,
            availableMemory = availableMemory,
            usagePercentage = (usedMemory.toDouble() / maxMemory * 100).toInt(),
        )
    }

    /**
     * 强制垃圾回收
     */
    fun forceGarbageCollection() {
        System.gc()
        System.runFinalization()
    }

    /**
     * 获取系统配置信息
     */
    fun getSystemConfig(context: Context): SystemConfig {
        val configuration = context.resources.configuration
        return SystemConfig(
            isLowRamDevice = isLowRamDevice(context),
            screenDensity = configuration.densityDpi,
            screenSize = getScreenSize(configuration),
            orientation = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "横屏" else "竖屏",
            nightMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
        )
    }

    private fun isLowRamDevice(context: Context): Boolean =
        try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                activityManager.isLowRamDevice
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }

    private fun getScreenSize(configuration: Configuration): String =
        when (configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) {
            Configuration.SCREENLAYOUT_SIZE_SMALL -> "小屏幕"
            Configuration.SCREENLAYOUT_SIZE_NORMAL -> "普通屏幕"
            Configuration.SCREENLAYOUT_SIZE_LARGE -> "大屏幕"
            Configuration.SCREENLAYOUT_SIZE_XLARGE -> "超大屏幕"
            else -> "未知"
        }

    data class MemoryInfo(
        val usedMemory: Long,
        val maxMemory: Long,
        val availableMemory: Long,
        val usagePercentage: Int,
    ) {
        fun formatUsedMemory(): String = formatBytes(usedMemory)

        fun formatMaxMemory(): String = formatBytes(maxMemory)

        fun formatAvailableMemory(): String = formatBytes(availableMemory)

        private fun formatBytes(bytes: Long): String {
            val kb = bytes / 1024
            val mb = kb / 1024
            return when {
                mb > 0 -> "${mb}MB"
                kb > 0 -> "${kb}KB"
                else -> "${bytes}B"
            }
        }
    }

    data class SystemConfig(
        val isLowRamDevice: Boolean,
        val screenDensity: Int,
        val screenSize: String,
        val orientation: String,
        val nightMode: Boolean,
    )
}

/**
 * 扩展函数，用于简化资源管理
 */
fun Activity.registerForResourceManagement() {
    ResourceManager.registerActivity(this::class.java.simpleName, this)
}

fun Activity.unregisterFromResourceManagement() {
    ResourceManager.unregisterActivity(this::class.java.simpleName)
}

fun Activity.createManagedScope(): CoroutineScope = ResourceManager.createScope(this::class.java.simpleName)

fun Activity.addManagedDisposable(disposable: () -> Unit) {
    ResourceManager.addDisposable(this::class.java.simpleName, disposable)
}
