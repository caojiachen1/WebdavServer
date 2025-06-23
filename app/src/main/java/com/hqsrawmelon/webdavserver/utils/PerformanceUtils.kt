package com.hqsrawmelon.webdavserver.utils

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 性能优化工具类
 */
object PerformanceUtils {
    
    /**
     * 缓存管理器 - 用于缓存昂贵的计算结果
     */
    private val cache = ConcurrentHashMap<String, Any>()
    
    /**
     * 获取缓存值，如果不存在则计算并缓存
     */
    suspend fun <T> getCached(
        key: String,
        computation: suspend () -> T
    ): T {
        @Suppress("UNCHECKED_CAST")
        return cache[key] as? T ?: run {
            val result = computation()
            cache[key] = result as Any
            result
        }
    }
    
    /**
     * 清除指定的缓存
     */
    fun clearCache(key: String) {
        cache.remove(key)
    }
    
    /**
     * 清除所有缓存
     */
    fun clearAllCache() {
        cache.clear()
    }
    
    /**
     * 防抖动函数 - 防止频繁操作
     */
    class Debouncer(private val waitMs: Long = 300L) {
        private var debounceJob: Job? = null
        
        fun debounce(coroutineScope: CoroutineScope, action: suspend () -> Unit) {
            debounceJob?.cancel()
            debounceJob = coroutineScope.launch {
                delay(waitMs)
                action()
            }
        }
    }
    
    /**
     * 节流函数 - 限制操作频率
     */
    class Throttler(private val intervalMs: Long = 1000L) {
        private var lastExecuteTime = 0L
        private var throttleJob: Job? = null
        
        fun throttle(coroutineScope: CoroutineScope, action: suspend () -> Unit) {
            val now = System.currentTimeMillis()
            if (now - lastExecuteTime >= intervalMs) {
                lastExecuteTime = now
                throttleJob?.cancel()
                throttleJob = coroutineScope.launch { action() }
            }
        }
    }
}

/**
 * Compose 性能优化扩展
 */

/**
 * 记忆化组合函数 - 避免不必要的重组
 */
@Composable
fun <T> rememberMemoized(
    key: Any?,
    calculation: () -> T
): T {
    return remember(key) { calculation() }
}

/**
 * 延迟状态更新 - 避免频繁的状态更新
 */
@Composable
fun <T> rememberDebouncedState(
    initialValue: T,
    delayMs: Long = 300L
): MutableState<T> {
    val state = remember { mutableStateOf(initialValue) }
    val debouncedState = remember { mutableStateOf(initialValue) }
    
    LaunchedEffect(state.value) {
        delay(delayMs)
        debouncedState.value = state.value
    }
    
    return object : MutableState<T> {
        override var value: T
            get() = debouncedState.value
            set(value) { state.value = value }
        
        override fun component1(): T = value
        override fun component2(): (T) -> Unit = { value = it }
    }
}

/**
 * 批量状态更新 - 减少重组次数
 */
class BatchStateUpdater<T>(
    private val initialStates: Map<String, T>
) {
    private val states = initialStates.toMutableMap()
    private val listeners = mutableMapOf<String, (T) -> Unit>()
    
    fun getValue(key: String): T? = states[key]
    
    fun updateState(key: String, value: T) {
        states[key] = value
    }
    
    fun batchUpdate(updates: Map<String, T>) {
        updates.forEach { (key, value) ->
            states[key] = value
        }
        // 批量通知更新
        updates.keys.forEach { key ->
            listeners[key]?.invoke(states[key]!!)
        }
    }
    
    fun addListener(key: String, listener: (T) -> Unit) {
        listeners[key] = listener
    }
}
