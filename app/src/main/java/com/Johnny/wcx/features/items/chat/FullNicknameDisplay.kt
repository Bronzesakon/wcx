package com.Johnny.wcx.features.items.chat

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.features.api.ui.WeConversationListViewApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import java.util.ArrayDeque
import java.util.WeakHashMap

/**
 * 让三处微信原生界面的昵称不再被截断成「...」，过长时自动换行：
 *
 *  1. 微信主界面「微信」标签页 —— 会话列表行的昵称（微信约 14 个汉字后截断）
 *  2. 微信主界面「通讯录」标签页 —— 联系人行的昵称（约 16 个汉字后截断）
 *  3. 好友聊天页 —— 顶部标题栏的昵称与状态行（各约 8 个汉字后截断）
 *
 * 三处都是微信自己的布局行为（`singleLine` + `ellipsize=END`），模块不重新画 UI，
 * 只在绑定/视图创建后把这些 TextView 放开成多行。除这三处外不碰任何其它控件。
 *
 * 关闭功能时会把放开过的 TextView 逐一还原成原状态（列表行是复用 View 的，
 * 不还原的话关掉开关后行里仍是多行）。
 */
@Feature(
    name = "完整显示昵称",
    categories = ["聊天"],
    description = "微信会话列表 / 通讯录 / 好友聊天页标题的昵称不再被截断成「...」，过长时自动换行显示"
)
object FullNicknameDisplay : SwitchFeature(),
    IResolveDex,
    WeConversationListViewApi.IBindViewListener {

    private const val TAG = "FullNicknameDisplay"

    /** 放开前的原始状态，供关闭功能时还原。 */
    private class TruncationState(
        val maxLines: Int,
        val singleLine: Boolean,
        val ellipsize: TextUtils.TruncateAt?,
        val height: Int,
    )

    /** 只记录被本功能改过的 TextView，WeakHashMap 让随行回收的 View 自然过期。 */
    private val released = WeakHashMap<TextView, TruncationState>()

    /** 通讯录单元格的绑定器（addressbook_cell）。 */
    private val classAddressItemConvert by dexClass {
        matcher {
            usingStrings("MicroMsg.Mvvm.AddressItemConvert", "onBindViewHolder ", "addressbook_cell")
        }
    }

    override fun onEnable() {
        WeConversationListViewApi.addListener(this)
        installAddressHook()
        installChatTitleHook()
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
    }

    // ---------------------------------------------------------------------------------------------
    // 1. 「微信」标签页：会话列表行
    // ---------------------------------------------------------------------------------------------

    override fun onBind(
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
        row: View,
        conversation: Any,
        context: WeConversationListViewApi.BindContext,
    ) {
        releaseNicknameIn(row as? ViewGroup ?: return)
    }

    // ---------------------------------------------------------------------------------------------
    // 2. 「通讯录」标签页：联系人行
    // ---------------------------------------------------------------------------------------------

    private fun installAddressHook() {
        if (classAddressItemConvert.isPlaceholder) {
            WeLogger.w(TAG, "AddressItemConvert 未定位，通讯录昵称保持原样")
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
            WeLogger.w(TAG, "通讯录行 hook 安装失败，昵称保持原样", it)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. 好友聊天页：顶部标题栏
    // ---------------------------------------------------------------------------------------------

    private fun installChatTitleHook() {
        runCatching {
            "com.tencent.mm.ui.chatting.ChattingUIFragment".toClass().reflekt()
                .firstMethod { name = "dealContentView" }.hookAfter {
                    val contentView = thisObject as? ViewGroup ?: return@hookAfter
                    // 标题栏在 dealContentView 返回后才填完，post 一帧再取。
                    contentView.post { releaseChatTitleBar(contentView) }
                }
        }.onFailure {
            WeLogger.w(TAG, "聊天页标题 hook 安装失败，昵称保持原样", it)
        }
    }

    /**
     * 聊天页标题栏：以「返回」按钮定位操作栏（与主题功能的做法一致），
     * 放开操作栏内所有 TextView —— 即昵称标题和下方的状态行（如「对方正在输入」），
     * 两者都会被微信按 8 个汉字截断。
     */
    private fun releaseChatTitleBar(contentView: ViewGroup) {
        val back = contentView.findViewWhich<View> {
            it.contentDescription == "返回"
        } ?: return
        val bar = back.parent as? ViewGroup ?: return
        forEachTextView(bar) { release(it) }
    }

    // ---------------------------------------------------------------------------------------------
    // 公共
    // ---------------------------------------------------------------------------------------------

    /**
     * 在一行（会话行 / 通讯录行）里找昵称 TextView：字号最大且有文字的 TextView。
     * 会话行里标题是最大字号，摘要、时间、未读数都更小，能稳定区分。
     */
    private fun releaseNicknameIn(root: ViewGroup) {
        var title: TextView? = null
        forEachTextView(root) { tv ->
            if (tv.text.isNullOrBlank()) return@forEachTextView
            if (title == null || tv.textSize > title!!.textSize) title = tv
        }
        title?.let { release(it) }
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
        tv.setSingleLine(false)
        tv.maxLines = Int.MAX_VALUE
        tv.ellipsize = null
        // 高度被微信写死的，改成 wrap 才能把第二行显示出来；MATCH_PARENT/WRAP_CONTENT 不动。
        if (lp != null && lp.height > 0) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            tv.layoutParams = lp
        }
    }
}
