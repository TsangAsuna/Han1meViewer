package com.yenaly.han1meviewer.util

import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Firebase 可用性闸门 + 手动初始化。
 *
 * ## 为什么需要它
 *
 * Firebase SDK 自带 `FirebaseInitProvider`（initOrder=100），会在
 * `Application.onCreate` **之前**自动 `FirebaseApp.initializeApp()`。
 * 随后 Analytics / Sessions / Crashlytics 在各自的线程池直接调用服务端 API。
 * 当 `google-services.json` 是占位配置（fork / CI / 本地构建）时，GMS 的
 * `Preconditions` 会在后台线程抛：
 *
 *   IllegalArgumentException: Please set a valid API key...
 *
 * 直接崩溃（CRASHX-CCF520F54DF2 / CRASHX-D15C365E8FCF）。
 *
 * 任何 Kotlin 侧守卫都拦不住这条路径 —— 它在我们的代码之前就跑完了。
 *
 * ## 方案
 *
 * 1. `AndroidManifest.xml` 用 `tools:node="remove"` 移除 FirebaseInitProvider，
 *    让 SDK 不再自动初始化。
 * 2. 这里读取生成的资源（`google_api_key` / `google_app_id`）判断配置真伪。
 * 3. 配置有效 → 手动 `FirebaseApp.initializeApp()`，Firebase 功能照常；
 *    配置是占位/缺失 → 什么都不做，Firebase 在整个进程里保持未初始化，
 *    所有调用点经 [guarded] 短路。
 */
object FirebaseGate {

    private const val TAG = "FirebaseGate"

    /** 与 CI workflow / 本地占位 google-services.json 对应 */
    private const val PLACEHOLDER_KEY_PREFIX = "AIzaSyPLACEHOLDER"

    @Volatile
    private var cachedEnabled: Boolean? = null

    /**
     * 在 Application.onCreate 中调用一次：
     * 配置有效则手动初始化 FirebaseApp，否则保持未初始化。
     */
    fun initializeIfConfigured(context: Context) {
        val appContext = context.applicationContext
        contextPackage = appContext.packageName
        if (!hasValidConfig(appContext)) {
            Log.i(
                TAG,
                "Firebase config is placeholder/absent — Firebase stays UNINITIALIZED " +
                    "for this process (no crash, features disabled)."
            )
            cachedEnabled = false
            return
        }
        try {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseApp.initializeApp(appContext)
            }
            cachedEnabled = true
            Log.i(TAG, "Firebase initialized with valid config.")
        } catch (e: Throwable) {
            Log.w(TAG, "FirebaseApp.initializeApp failed; disabling Firebase.", e)
            cachedEnabled = false
        }
    }

    /** 当前进程的 Firebase 是否已用有效配置初始化 */
    fun isEnabled(context: Context): Boolean {
        cachedEnabled?.let { return it }
        val enabled = hasValidConfig(context.applicationContext) &&
            FirebaseApp.getApps(context.applicationContext).isNotEmpty()
        cachedEnabled = enabled
        return enabled
    }

    /**
     * 读取 google-services 插件生成的字符串资源判断配置真伪。
     * 插件未应用 / 资源缺失时同样返回 false。
     */
    private fun hasValidConfig(context: Context): Boolean {
        return try {
            if (contextPackage.isEmpty()) contextPackage = context.packageName
            val res = context.resources
            val apiKey = res.getStringByName("google_api_key").orEmpty()
            val appId = res.getStringByName("google_app_id").orEmpty()

            val valid = apiKey.isNotBlank() &&
                !apiKey.startsWith(PLACEHOLDER_KEY_PREFIX) &&
                appId.isNotBlank()

            if (!valid) {
                Log.w(
                    TAG,
                    "Invalid Firebase config (apiKey=${mask(apiKey)}, appId=$appId)."
                )
            }
            valid
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to read Firebase config resources; treating as invalid.", e)
            false
        }
    }

    private fun Resources.getStringByName(name: String): String? {
        val id = getIdentifier(name, "string", contextPackage)
        if (id == 0) return null
        return try {
            getString(id)
        } catch (_: Throwable) {
            null
        }
    }

    /** 由 initializeIfConfigured 在首次调用时写入 */
    @Volatile
    private var contextPackage: String = ""

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
