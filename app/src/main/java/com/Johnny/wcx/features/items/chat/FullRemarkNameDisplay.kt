package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.data
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.ui.WeConversationListViewApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap

/**
 * 让三处微信原生界面的「名字」完整显示, 过长时自动换行而不是截断成「...」:
 *
 *  1. 微信主界面「微信」标签页 —— 会话列表行的名字 (微信约 14 字后截断)
 *  2. 微信主界面「通讯录」标签页 —— 联系人行的名字 (约 16 字后截断)
 *  3. 好友聊天页 —— 顶部标题栏的名字 (约 8+8 字后截断)
 *
 * 微信这三处显示的「名字」优先取**备注名** (未设备注时退化为昵称), 因此功能命名为
 * 「完整显示备注名」。
 *
 * 截断分两种, 处理方式不同 (8.0.76 设备日志实锤):
 *  - **渲染级截断** (会话列表 / 通讯录): TextView 的 singleLine + ellipsize,
 *    text 本身仍是完整的 —— 放开单行/省略号/写死高度即可完整显示。
 *  - **代码级截断** (聊天页标题): 微信在 setText 之前就把字符串改成了
 *    「头部...尾部」—— 注意是三个 ASCII 点, 不是 Android 原生 ellipsize 的
 *    单字符「…」, 说明文本自身已残缺, 放开 TextView 无效。需挂 TextWatcher,
 *    发现省略号后用备注名 (rcontact.conRemark → nickname) 还原完整文本。
 *
 * 关闭功能时会把放开过的 TextView 逐一还原成原状态 (列表行是复用 View 的,
 * 不还原的话关掉开关后行里仍是多行), 并摘掉 TextWatcher。
 */
@Feature(
    name = "完整显示备注名",
    categories = ["聊天"],
    description = "微信会话列表 / 通讯录 / 好友聊天页标题的备注名不再被截断成「...」，过长时自动换行显示"
)
object FullRemarkNameDisplay : SwitchFeature(),
    IResolveDex,
    WeConversationListViewApi.IBindViewListener {

    private const val TAG = "FullRemarkNameDisplay"

    /** 记住的会话显示名 / 备注名查询缓存的上限。 */
    private const val NAME_CACHE_LIMIT = 128

    /** 放开前的原始状态，供关闭功能时还原。 */
    private class TruncationState(
        val maxLines: Int,
        val singleLine: Boolean,
        val ellipsize: TextUtils.TruncateAt?,
        val height: Int,
    )

    /** 只记录被本功能改过的 TextView，WeakHashMap 让随行回收的 View 自然过期。 */
    private val released = WeakHashMap<TextView, TruncationState>()

    /** 挂在标题 TextView 上的还原器，关闭功能时摘除。 */
    private val restorers = WeakHashMap<TextView, TitleRestorer>()

    /** 通讯录单元格的绑定器（addressbook_cell）。 */
    private val classAddressItemConvert by dexClass {
        matcher {
            usingStrings("MicroMsg.Mvvm.AddressItemConvert", "onBindViewHolder ", "addressbook_cell")
        }
    }

    /** ChattingContext (8.0.65 是 az4.c, 8.0.76 是 fd5.d)。 */
    private val classChattingContext by dexClass {
        matcher {
            usingEqStrings("MicroMsg.ChattingContext", "[notifyDataSetChange]")
        }
    }

    /** ChattingContext.getTalker()。 */
    private val methodChattingContextGetTalker by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classChattingContext.data.name)
            usingEqStrings("getTalker returns null.")
        }
    }

    /** 当前聊天对象的 wxid (群聊为群 id)。 */
    @Volatile
    private var currentTalker: String? = null

    /** username -> 完整显示名 (会话行绑定时的文本, 渲染级截断下即完整名)。 */
    private val rememberedNames: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > NAME_CACHE_LIMIT
        }
    )

    /** username -> 备注名查询结果缓存, 切换聊天对象时清空。 */
    private val displayNameCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > NAME_CACHE_LIMIT
        }
    )

    /** 会话行代码级截断只记一次日志, 免得滚动时刷屏。 */
    private var rowRestoreLogged = false

    /** 还原失败按文本去重, 免得同一标题反复刷诊断日志。 */
    private var lastFailedRestoreText: String? = null

    override fun onEnable() {
        WeConversationListViewApi.addListener(this)
        installAddressHook()
        installChatTitleHook()
        installTalkerCapture()
        WeLogger.i(TAG, "enabled: 会话列表 / 通讯录 / 聊天页标题的备注名完整显示")
    }

    override fun onDisable() {
        WeConversationListViewApi.removeListener(this)
        released.forEach { (tv, state) ->
            tv.setSingleLine(state.singleLine)
            tv.maxLines = state.maxLines
            tv.ellipsize = state.ellipsize
            val lp = tv.layoutParams
            if (lp != null && lp.height != state.height) {
                lp.height = state.height
                tv.layoutParams = lp
            }
        }
        released.clear()
        restorers.forEach { (tv, watcher) -> tv.removeTextChangedListener(watcher) }
        restorers.clear()
        currentTalker = null
        rememberedNames.clear()
        displayNameCache.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // 1. 「微信」标签页: 会话列表行
    // ---------------------------------------------------------------------------------------------

    override fun onBind(
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
        row: View,
        conversation: Any,
        context: WeConversationListViewApi.BindContext,
    ) {
        val title = findRowTitle(row as? ViewGroup ?: return) ?: return
        release(title)
        val text = title.text?.toString() ?: return
        val username = runCatching {
            conversation.reflekt()
                .firstFieldOrNull { name = "field_username"; superclass() }
                ?.get() as? String
        }.getOrNull() ?: return

        if (!text.contains("...")) {
            // 渲染级截断: 行文本即完整显示名, 记住供聊天页标题还原
            rememberName(username, text)
            return
        }
        // 代码级截断: 行文本自身已残缺, 用备注名直接还原
        val full = restoreIfTruncated(text, displayNameOf(username)) ?: return
        title.setText(full)
        rememberName(username, full)
        if (!rowRestoreLogged) {
            rowRestoreLogged = true
            WeLogger.i(TAG, "会话行备注名被代码级截断，已还原: '$text' -> '$full'")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. 「通讯录」标签页: 联系人行
    // ---------------------------------------------------------------------------------------------

    private fun installAddressHook() {
        if (classAddressItemConvert.isPlaceholder) {
            WeLogger.w(TAG, "AddressItemConvert 未定位，通讯录备注名保持原样")
            return
        }
        runCatching {
            classAddressItemConvert.clazz.reflekt()
                .firstMethod {
                    parameters("androidx.recyclerview.widget.RecyclerView", View::class.java)
                }.hookAfter {
                    val item = args.getOrNull(1) as? ViewGroup ?: return@hookAfter
                    releaseNicknameIn(item)
                }
        }.onFailure {
            WeLogger.w(TAG, "通讯录行 hook 安装失败，备注名保持原样", it)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. 好友聊天页: 顶部标题栏
    // ---------------------------------------------------------------------------------------------

    private fun installChatTitleHook() {
        runCatching {
            "com.tencent.mm.ui.chatting.ChattingUIFragment".toClass().reflekt()
                .firstMethod { name = "dealContentView" }.hookAfter {
                    // dealContentView 的形态在版本间变过: 可能是 Fragment 方法 (thisObject 是
                    // Fragment)、参数里带内容视图、或 thisObject 本身就是视图, 三种都兼容。
                    val contentView = args.getOrNull(0) as? ViewGroup
                        ?: thisObject as? ViewGroup
                        ?: (thisObject as? Fragment)?.view as? ViewGroup
                    if (contentView == null) {
                        WeLogger.w(TAG, "dealContentView 已触发但取不到内容视图，标题备注名可能保持原样")
                        return@hookAfter
                    }
                    // 独立聊天窗口 (ChattingUI Activity) 的 intent 带 Chat_User
                    captureTalkerFromHost(contentView.context)
                    // 标题栏在 dealContentView 返回后才填完, post 一帧再取
                    contentView.post { releaseChatTitleBar(contentView) }
                }
        }.onFailure {
            WeLogger.w(TAG, "聊天页标题 hook 安装失败，标题备注名保持原样", it)
        }

        // onResume 兜底: 微信在标题数据异步到位后才设置截断文本; 且每次回到聊天页都重新
        // 放开一次 (dealContentView 只在页面创建时走, 旧版靠它单点处理, 标题TextView 被
        // 重建或微信后配置 singleLine 时就漏了)。
        runCatching {
            "com.tencent.mm.ui.chatting.ChattingUIFragment".toClass().reflekt()
                .firstMethod { name = "onResume"; superclass() }.hookAfter {
                    val fragment = thisObject as? Fragment ?: return@hookAfter
                    captureTalker(fragment)
                    (fragment.view as? ViewGroup)?.let { releaseChatTitleBar(it) }
                }
        }.onFailure {
            WeLogger.w(TAG, "聊天页 onResume hook 安装失败，标题备注名兜底失效", it)
        }
    }

    /**
     * 全局捕获 ChattingContext.getTalker 的返回值作为当前聊天对象: 标题文本是微信自己
     * setText 的, 那一刻拿不到稳定的 talker; 而 getTalker 在聊天页生命周期里会被反复
     * 调用, 最近一次非空结果就是当前聊天对象。
     */
    private fun installTalkerCapture() {
        if (methodChattingContextGetTalker.isPlaceholder) {
            WeLogger.w(TAG, "ChattingContext.getTalker 未定位，聊天标题还原只能依赖会话列表缓存")
            return
        }
        runCatching {
            methodChattingContextGetTalker.hookAfter {
                (result as? String)?.takeIf { it.isNotEmpty() }?.let { setCurrentTalker(it) }
            }
        }.onFailure {
            WeLogger.w(TAG, "getTalker hook 安装失败，聊天标题还原只能依赖会话列表缓存", it)
        }
    }

    /** 独立聊天窗口 (ChattingUI Activity) 的 intent 带 Chat_User; LauncherUI 托管的聊天页没有。 */
    private fun captureTalkerFromHost(context: android.content.Context?) {
        val talker = (context as? Activity)?.intent?.getStringExtra("Chat_User") ?: return
        if (talker.isNotEmpty()) setCurrentTalker(talker)
    }

    /** 从 Fragment 字段里找 ChattingContext, 调 getTalker 拿当前聊天对象。 */
    private fun captureTalker(fragment: Fragment) {
        if (classChattingContext.isPlaceholder || methodChattingContextGetTalker.isPlaceholder) return
        val context = runCatching {
            fragment.reflekt().fields { superclass = true }.map { it.get() }
                .firstOrNull { it != null && classChattingContext.clazz.isInstance(it) }
        }.getOrNull() ?: return
        val talker = runCatching {
            methodChattingContextGetTalker.method.invoke(context) as? String
        }.getOrNull()
        if (!talker.isNullOrEmpty()) setCurrentTalker(talker)
    }

    private fun setCurrentTalker(talker: String) {
        if (currentTalker != talker) {
            currentTalker = talker
            displayNameCache.clear()
        }
    }

    /**
     * 聊天页标题栏: 放开操作栏内所有 TextView 的单行截断, 并给它们挂上还原器 ——
     * 标题 (备注名) 与下方的状态行 (如「对方正在输入」) 都会被微信按 8+8 字截断。
     */
    private fun releaseChatTitleBar(contentView: ViewGroup) {
        val bar = chatActionBarRoot(contentView) ?: run {
            WeLogger.w(TAG, "聊天页操作栏未定位，标题备注名保持原样")
            return
        }
        var processed = 0
        forEachTextView(bar) { tv ->
            if (!released.containsKey(tv)) {
                release(tv)
                val watcher = TitleRestorer(tv, ::restoreFullTitle, ::applyUnwrap)
                restorers[tv] = watcher
                tv.addTextChangedListener(watcher)
                processed++
            }
            // 标题可能在我们挂监听前就已设置, 立即补一次还原
            val text = tv.text?.toString()
            if (!text.isNullOrBlank() && text.contains("...")) {
                restoreFullTitle(text)?.let { tv.setText(it) }
            }
        }
        if (processed > 0) {
            WeLogger.i(TAG, "聊天页操作栏处理 $processed 个 TextView (${bar.javaClass.name})")
        }
    }

    /**
     * 定位聊天页操作栏容器: 优先 ChattingUILayout 的第一个子视图 (与主题功能同款定位,
     * 整个操作栏子树, 含「返回」按钮与标题); 定位不到时从「返回」按钮逐级向上, 取最远的
     * 仍含文字的祖先容器。
     */
    private fun chatActionBarRoot(contentView: ViewGroup): ViewGroup? {
        val layout = contentView.findViewWhich<View> {
            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout"
        } as? ViewGroup
        val barFromLayout = layout?.getChildAt(0) as? ViewGroup
        if (barFromLayout != null && hasNonBlankText(barFromLayout)) return barFromLayout

        val back = contentView.findViewWhich<View> { it.contentDescription == "返回" } ?: return barFromLayout
        var parent = back.parent as? ViewGroup
        var best: ViewGroup? = null
        var depth = 0
        while (parent != null && depth < 4) {
            if (hasNonBlankText(parent)) best = parent
            parent = parent.parent as? ViewGroup
            depth++
        }
        return best ?: barFromLayout
    }

    private fun hasNonBlankText(root: View): Boolean {
        var found = false
        forEachTextView(root) { tv -> if (!tv.text.isNullOrBlank()) found = true }
        return found
    }

    // ---------------------------------------------------------------------------------------------
    // 公共
    // ---------------------------------------------------------------------------------------------

    /**
     * 在一行（会话行 / 通讯录行）里找名字 TextView：字号最大且有文字的 TextView。
     * 会话行里标题是最大字号，摘要、时间、未读数都更小，能稳定区分。
     */
    private fun findRowTitle(root: ViewGroup): TextView? {
        var title: TextView? = null
        forEachTextView(root) { tv ->
            if (tv.text.isNullOrBlank()) return@forEachTextView
            if (title == null || tv.textSize > title!!.textSize) title = tv
        }
        return title
    }

    private fun releaseNicknameIn(root: ViewGroup) {
        findRowTitle(root)?.let { release(it) }
    }

    private inline fun forEachTextView(root: View, action: (TextView) -> Unit) {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (v is TextView) action(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
        }
    }

    /** 放开单个 TextView 的单行截断；记下原状态，关闭功能时还原。 */
    private fun release(tv: TextView) {
        if (released.containsKey(tv)) return
        val lp = tv.layoutParams
        released[tv] = TruncationState(
            maxLines = tv.maxLines,
            singleLine = tv.isSingleLine,
            ellipsize = tv.ellipsize,
            height = lp?.height ?: 0,
        )
        applyUnwrap(tv)
    }

    private fun applyUnwrap(tv: TextView) {
        tv.setSingleLine(false)
        tv.maxLines = Int.MAX_VALUE
        tv.ellipsize = null
        // 高度被微信写死的，改成 wrap 才能把第二行显示出来；MATCH_PARENT/WRAP_CONTENT 不动。
        val lp = tv.layoutParams
        if (lp != null && lp.height > 0) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            tv.layoutParams = lp
        }
    }

    private fun rememberName(username: String, name: String) {
        rememberedNames[username] = name
    }

    /** 备注名优先的显示名 (rcontact.conRemark → nickname)，带缓存。 */
    private fun displayNameOf(username: String): String? {
        displayNameCache[username]?.let { return it }
        val name = runCatching { WeDatabaseApi.getDisplayName(username) }.getOrNull() ?: return null
        displayNameCache[username] = name
        return name
    }

    /** 解析「头部...尾部」; 省略号不在中部或头尾为空时返回 null。 */
    private fun parseMiddleTruncated(text: String): Pair<String, String>? {
        val idx = text.indexOf("...")
        if (idx <= 0 || idx + 3 >= text.length) return null
        val head = text.substring(0, idx)
        val tail = text.substring(idx + 3)
        return if (head.isNotEmpty() && tail.isNotEmpty()) head to tail else null
    }

    /**
     * 代码级截断还原 (会话行用): [truncated] 形如「头部...尾部」时, 用 [fullName] 验证并
     * 还原。必须中部省略、头尾非空、完整名更长且首尾吻合, 避免误伤含「...」的正常文本。
     */
    private fun restoreIfTruncated(truncated: String, fullName: String?): String? {
        if (fullName.isNullOrEmpty()) return null
        val (head, tail) = parseMiddleTruncated(truncated) ?: return null
        if (fullName.length <= truncated.length) return null
        if (!fullName.startsWith(head) || !fullName.endsWith(tail)) return null
        return fullName
    }

    /**
     * 聊天页标题的代码级截断还原: 文本形如「头部...尾部」时, 依次尝试
     * ① 当前聊天对象的备注名 (查库) ② 会话列表记住的完整显示名, 首尾吻合才采用。
     */
    private fun restoreFullTitle(truncated: String): String? {
        val (head, tail) = parseMiddleTruncated(truncated) ?: return null
        val talker = currentTalker
        if (!talker.isNullOrEmpty()) {
            displayNameOf(talker)?.let { full ->
                if (full.length > truncated.length && full.startsWith(head) && full.endsWith(tail)) {
                    WeLogger.i(TAG, "聊天标题还原: '$truncated' -> '$full'")
                    return full
                }
            }
        }
        val remembered = synchronized(rememberedNames) {
            rememberedNames.values.toList()
                .firstOrNull { it.length > truncated.length && it.startsWith(head) && it.endsWith(tail) }
        }
        if (remembered != null) {
            WeLogger.i(TAG, "聊天标题还原(会话列表缓存): '$truncated' -> '$remembered'")
            return remembered
        }
        if (truncated != lastFailedRestoreText) {
            lastFailedRestoreText = truncated
            WeLogger.i(
                TAG,
                "聊天标题含省略号但未找到完整备注名: '$truncated', talker=$talker, 缓存 ${rememberedNames.size} 条"
            )
        }
        return null
    }

    /**
     * 聊天页标题的代码级截断还原器: 微信在 setText 之前已把备注名改成「头部...尾部」,
     * 文本监听发现省略号时用备注名还原, 并放开单行截断让完整名换行显示。
     */
    private class TitleRestorer(
        private val tv: TextView,
        private val restore: (String) -> String?,
        private val unwrap: (TextView) -> Unit,
    ) : TextWatcher {
        private var lastRestored: String? = null

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {
            val text = s?.toString() ?: return
            if (text == lastRestored) return
            val full = restore(text) ?: return
            lastRestored = full
            tv.setText(full)
            unwrap(tv)
        }
    }
}
