package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.utils.allViews
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import org.luckypray.dexkit.DexKitBridge
import java.util.Collections
import java.util.WeakHashMap

/**
 * 打开聊天里的图片 / 视频时, 自动点击「查看原图 / 查看原视频」按钮, 直接看原画质。
 *
 * 旧实现只 hook 了 `ImageGalleryUI` 的两个方法 (`setImageHdImgBtnVisibility` /
 * `checkNeedShowOriginVideoBtn`), 并在 hook 里枚举 thisObject 的 **Button 类型字段**,
 * 文字命中「查看原图 / 查看原视频」就 `performClick()`。设备实测「查看原视频」不生效,
 * 而旧实现没有任何日志, 无从判断卡在哪一环。反查微信 8.0.76 安装包后确认:
 *
 *  - `checkNeedShowOriginVideoBtn` 在 dex 里只是**日志文案**
 *    (`checkNeedShowOriginVideoBtn: msg is expired`), 并不是稳定的可 hook 方法名;
 *    且从命名看它只负责**判断**是否需要显示按钮, 真正的 setVisibility 在调用方 ——
 *    hookAfter 时按钮很可能还是 GONE, 旧代码那句 `if (imgBtn.isVisible)` 直接跳过;
 *  - 「查看原视频」文案来自**字符串资源** (中文 `查看原视频`, 英文 View Full Video),
 *    不在 dex 里, 无法按字符串定位控件, 且运行时带体积后缀 (「查看原视频 64MB」);
 *  - `ImageGalleryUI` 类名**未混淆**, 自身声明了 `onCreate` / `onResume`。
 *
 * 因此改为**不依赖按钮类型、也不依赖单个 dex 方法**:
 *  - 从 `ImageGalleryUI` 的 onCreate / onResume 起一个轻量轮询, 直接在视图树里找
 *    「可见且文案命中关键字」的 TextView (Button 也是 TextView 子类), 点击它
 *    (或它最近的可点击祖先);
 *  - 原先定位到的两个 dex 方法仍然 hook, 作为「按钮状态刚更新」的额外续期信号;
 *  - 轮询在页面销毁 / 超时 / 功能关闭时停止; 同一控件 + 同一文案只点一次。
 */
@Feature(
    name = "自动查看原图/原视频",
    categories = ["聊天"],
    description = "打开聊天里的图片 / 视频时自动点击「查看原图 / 查看原视频」，直接看原画质"
)
object AutoViewOriginalMedia : SwitchFeature(), IResolveDex {

    private const val TAG = "AutoViewOriginalMedia"

    /** 聊天图片 / 视频查看页 (8.0.76 未被混淆, 与 Themes.kt 的运行时判定一致)。 */
    private const val GALLERY_UI_CLASS = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"

    /**
     * 更名前的功能名。开关状态以**功能名**为 key 持久化 ([SwitchFeature.startup] 用
     * `WePrefs.getBoolOrDef(name, ...)`), 沿用旧 key 的状态做默认值, 避免改名把已开启的
     * 开关静默重置 (v281 → v282 已经踩过这个坑)。
     */
    private const val LEGACY_NAME = "自动查看原图"

    /** 按钮文案关键字 (中英双语兜底)。 */
    private val KEYWORDS = listOf(
        "查看原图", "查看原视频",
        "Full Image", "Original quality",
    )

    private const val INITIAL_DELAY_MS = 200L
    private const val WATCH_INTERVAL_MS = 600L

    /**
     * 单次起轮询后的最长观察时长。图片/视频页内左右滑动**不会**重走 onResume,
     * 所以窗口要足够长, 覆盖「打开页面后过一阵才划到视频」的情况;
     * 每次 onResume / 按钮状态更新会重新续期。
     */
    private const val WATCH_TIMEOUT_MS = 180_000L

    /** Activity -> 轮询截止时间 (uptimeMillis)。命中全部结束 / 超时 / 页面销毁后移除。 */
    private val deadlines: MutableMap<Activity, Long> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * 已点过的按钮 (view 身份 + 文案)。轮询窗口内按钮可能长期可见 (下载中),
     * 用这个避免反复点击; 换到另一个视频时文案/控件不同, 仍会正常点击。
     */
    private val clicked = Collections.synchronizedSet(HashSet<String>())

    private val methodSetImageHdImgBtnVisibility by dexMethod()
    private val methodCheckNeedShowOriginVideoBtn by dexMethod()

    /** 改名后沿用旧 key 的开关状态, 用户在新名下拨动过开关后以新 key 为准。 */
    override val defaultEnabled: Boolean
        get() = runCatching { WePrefs.getBoolOrDef(LEGACY_NAME, false) }.getOrDefault(false)

    /** 启动时把开关判定结果与新 / 旧 key 的存在情况打进日志, 便于下次一眼分辨失效环节。 */
    override val shouldEnableOnStartup: Boolean
        get() = _isEnabled.also { on ->
            val newKey = runCatching { WePrefs.containsKey(name) }.getOrDefault(false)
            val oldKey = runCatching { WePrefs.containsKey(LEGACY_NAME) }.getOrDefault(false)
            WeLogger.i(
                TAG,
                "startup: 开关=$on (新key '$name' 存在=$newKey, 旧key '$LEGACY_NAME' 存在=$oldKey)"
            )
        }

    override fun resolveDex(dexKit: DexKitBridge) {
        // 图片: 优先 setHdImageActionDownloadable, 回退 setImageHdImgBtnVisibility
        runCatching {
            val imageResults = dexKit.findMethod {
                matcher {
                    declaredClass = GALLERY_UI_CLASS
                    usingEqStrings("setHdImageActionDownloadable")
                }
            }.ifEmpty {
                dexKit.findMethod {
                    matcher {
                        declaredClass = GALLERY_UI_CLASS
                        usingEqStrings("setImageHdImgBtnVisibility")
                    }
                }
            }
            imageResults.firstOrNull()?.let { methodSetImageHdImgBtnVisibility.setDescriptor(it) }
        }.onFailure { WeLogger.w(TAG, "定位图片原图按钮方法失败: ${it.message}") }

        // 视频: checkNeedShowOriginVideoBtn。allowFailure = true, 多结果时取首个而不是抛异常
        runCatching {
            methodCheckNeedShowOriginVideoBtn.find(dexKit, allowFailure = true) {
                matcher {
                    declaredClass = GALLERY_UI_CLASS
                    usingEqStrings("checkNeedShowOriginVideoBtn")
                }
            }
        }.onFailure { WeLogger.w(TAG, "定位原视频按钮方法失败: ${it.message}") }
    }

    override fun onEnable() {
        installGalleryLifecycleHook()
        installOriginStateHooks()
        WeLogger.i(TAG, "enabled: 打开图片 / 视频时自动点击「查看原图 / 查看原视频」")
    }

    override fun onDisable() {
        deadlines.clear()
        clicked.clear()
    }

    // ─────────────────────────────── 触发入口 ───────────────────────────────

    /** 主入口: 图片 / 视频查看页的 Activity 生命周期。 */
    private fun installGalleryLifecycleHook() {
        val clazz = runCatching { GALLERY_UI_CLASS.toClass() }.getOrNull()
        if (clazz == null) {
            WeLogger.w(TAG, "未找到 $GALLERY_UI_CLASS, 仅依赖 dex 方法触发轮询")
            return
        }

        var hooked = 0
        listOf("onCreate" to 1, "onResume" to 0).forEach { (methodName, paramCount) ->
            val method = runCatching {
                clazz.reflekt().firstMethod { name = methodName; parameterCount = paramCount }
            }.getOrNull() ?: runCatching {
                clazz.reflekt().firstMethod { name = methodName; parameterCount = paramCount; superclass() }
            }.getOrNull() ?: return@forEach

            method.hookAfter {
                // 若命中的是父类实现 (如 Activity.onCreate), 会收到全部 Activity 的回调, 需按类名过滤
                val activity = thisObject as? Activity ?: return@hookAfter
                if (activity.javaClass.name != GALLERY_UI_CLASS) return@hookAfter
                armWatcher(activity)
            }
            hooked++
        }
        if (hooked > 0) {
            WeLogger.i(TAG, "已 hook $GALLERY_UI_CLASS 的 $hooked 个生命周期方法")
        } else {
            WeLogger.w(TAG, "未能 hook $GALLERY_UI_CLASS 的生命周期方法, 仅依赖 dex 方法触发轮询")
        }
    }

    /** 原有两个 dex 方法仍 hook, 作为「按钮状态刚更新」的额外续期信号。 */
    private fun installOriginStateHooks() {
        listOf(methodSetImageHdImgBtnVisibility, methodCheckNeedShowOriginVideoBtn).forEach { method ->
            if (method.isPlaceholder) {
                WeLogger.w(TAG, "${method.key} 未定位到, 跳过 (仅靠生命周期轮询)")
                return@forEach
            }
            method.hookAfter {
                val activity = thisObject as? Activity ?: return@hookAfter
                armWatcher(activity)
            }
        }
    }

    // ─────────────────────────────── 轮询与点击 ───────────────────────────────

    /** 起（或续期）一个轮询任务; 同一页面只跑一个任务。 */
    private fun armWatcher(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val deadline = SystemClock.uptimeMillis() + WATCH_TIMEOUT_MS
        // put 返回非 null 说明已有任务在跑, 这里只续期
        if (deadlines.put(activity, deadline) != null) return
        decor.postDelayed(watchTask(activity, decor), INITIAL_DELAY_MS)
    }

    private fun watchTask(activity: Activity, decor: View): Runnable = object : Runnable {
        override fun run() {
            try {
                val deadline = deadlines[activity] ?: return // 已停止 (页面销毁 / 关闭功能)
                if (activity.isFinishing || activity.isDestroyed) {
                    deadlines.remove(activity)
                    return
                }

                clickOriginButton(decor)

                if (SystemClock.uptimeMillis() >= deadline) {
                    deadlines.remove(activity)
                    return
                }
                decor.postDelayed(this, WATCH_INTERVAL_MS)
            } catch (t: Throwable) {
                deadlines.remove(activity)
                WeLogger.w(TAG, "原图/原视频轮询异常: ${t.message}")
            }
        }
    }

    /** 在视图树里找可见且文案命中关键字的 TextView, 点击它（或最近的可点击祖先）。 */
    private fun clickOriginButton(root: View) {
        for (view in root.allViews) {
            val textView = view as? TextView ?: continue
            if (!textView.isShown) continue
            val text = textView.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) continue

            val keyword = KEYWORDS.firstOrNull { text.contains(it, ignoreCase = true) } ?: continue

            // 同一个控件 + 同一段文案只点一次 (按钮下载期间会一直可见)
            val key = "${System.identityHashCode(textView)}|$text"
            if (!clicked.add(key)) continue
            if (clicked.size > 128) clicked.clear()

            clickTarget(textView).performClick()
            WeLogger.i(TAG, "已自动点击「$keyword」: $text (${textView.javaClass.simpleName})")
        }
    }

    /** 按钮本体可能不可点击（文字是子 TextView），向上找最近的可点击祖先。 */
    private fun clickTarget(view: View): View {
        var current: View? = view
        var depth = 0
        while (current != null && depth <= 3) {
            if (current.isClickable) return current
            current = current.parent as? View
            depth++
        }
        return view
    }
}
