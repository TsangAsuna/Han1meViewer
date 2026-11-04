package com.yenaly.han1meviewer.util

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp

/**
 * Firebase 可用性闸门。
 *
 * 背景：fork / 本地 / CI 构建可能没有真实的 google-services.json，
 * 此时 GMS 的 Preconditions 会在后台线程调用 Firebase 服务端 API 时抛
 * `IllegalArgumentException: Please set a valid API key...`，
 * 直接把 App 打崩（CrashX-CCF520F54DF2）。
 *
 * 判定规则：FirebaseOptions 的 apiKey 为空 / 是占位值 / applicationId 为空
 * 都视为不可用。不可用时所有 Firebase 调用点必须短路，绕开而不是崩溃。
 *
 * 判定时机：FirebaseInitProvider 已在 Application.onCreate 之前完成初始化，
 * 因此这里读到的就是最终配置。
 */
object FirebaseGate {

    private const val TAG = "FirebaseGate"

    /** 与 CI workflow 写入的占位 google-services.json 对应 */
    private const val PLACEHOLDER_KEY_PREFIX = "AIzaSyPLACEHOLDER"

    @Volatile
    private var cachedEnabled: Boolean? = null

    /** 当前进程的 Firebase 配置是否可用 */
    fun isEnabled(context: Context): Boolean {
        cachedEnabled?.let { return it }
        val enabled = computeEnabled(context.applicationContext)
        cachedEnabled = enabled
        return enabled
    }

    private fun computeEnabled(context: Context): Boolean {
        return try {
            val app = FirebaseApp.getApps(context).firstOrNull()
            if (app == null) {
                Log.w(TAG, "Firebase not initialized; disabling Firebase features.")
                return false
            }
            val options = app.options
            val apiKey = options.apiKey.orEmpty()
            val appId = options.applicationId.orEmpty()

            val valid = apiKey.isNotBlank() &&
                !apiKey.startsWith(PLACEHOLDER_KEY_PREFIX) &&
                appId.isNotBlank()

            if (!valid) {
                Log.w(
                    TAG,
                    "Placeholder Firebase config detected (apiKey=${mask(apiKey)}, " +
                        "appId=$appId); Firebase features disabled to avoid GMS crash."
                )
            }
            valid
        } catch (e: Throwable) {
            // 任何异常都按不可用处理：宁可少遥测，也不能崩
            Log.w(TAG, "Failed to determine Firebase availability; disabling.", e)
            false
        }
    }

    /**
     * 仅在 Firebase 可用时执行 [block]，异常吞掉记日志。
     * 未执行或抛异常时返回 null。
     */
    fun <T> guarded(context: Context, block: () -> T): T? {
        if (!isEnabled(context)) return null
        return try {
            block()
        } catch (e: Throwable) {
            Log.w(TAG, "Firebase call failed and was swallowed.", e)
            null
        }
    }

    /** 供业务判断是否能走 Firebase 路径 */
    fun canUse(context: Context): Boolean = isEnabled(context)

    fun invalidate() {
        cachedEnabled = null
    }

    private fun mask(key: String): String = when {
        key.isEmpty() -> "<empty>"
        key.length <= 12 -> "***"
        else -> key.take(8) + "..." + key.takeLast(4)
    }
}
