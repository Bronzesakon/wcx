package com.Johnny.wcx.features.core

import android.content.Context
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger

abstract class SwitchFeature : BaseFeature() {

    companion object {
        /** 带分类总开关的分类名（见 [Preferences.hiddenContactsMasterEnabled]）。 */
        const val CATEGORY_WITH_MASTER_SWITCH = "密友功能"
    }

    /** Default state when the user has never toggled this feature. */
    protected open val defaultEnabled: Boolean = false

    /** Whether this feature should load in the current process. Defaults to the main process only. */
    protected open val shouldLoadInCurrentProcess: Boolean
        get() = TargetProcesses.isInMain

    /** Whether the feature should be active at startup, given the cached preference. */
    protected open val shouldEnableOnStartup: Boolean
        get() = _isEnabled

    final override fun startup() {
        if (!shouldLoadInCurrentProcess) return
        // 「密友功能」分类有总开关：关掉总开关时整个分类都不加载（各功能自身开关状态保留）。
        if (CATEGORY_WITH_MASTER_SWITCH in categories && !Preferences.hiddenContactsMasterEnabled) return
        _isEnabled = WePrefs.getBoolOrDef(name, defaultEnabled)
        if (shouldEnableOnStartup) enable()
    }

    /** Cached user preference (desired state). Distinct from [isActive], the runtime truth. */
    @Suppress("PropertyName")
    protected var _isEnabled = false

    var isEnabled
        get() = _isEnabled
        set(value) {
            if (_isEnabled == value) return
            _isEnabled = value
            if (value) {
                WeLogger.i("SwitchFeature", "enabling $displayName...")
                enable()
            } else {
                WeLogger.i("SwitchFeature", "disabling $displayName...")
                disable()
            }
        }

    private var toggleCompletionCallback: Runnable? = null

    open fun onBeforeToggle(newState: Boolean, context: Context): Boolean = true

    fun setToggleCompletionCallback(callback: Runnable) {
        toggleCompletionCallback = callback
    }

    fun applyToggle(newState: Boolean) {
        WePrefs.putBool(name, newState)
        isEnabled = newState
        toggleCompletionCallback?.run()
    }
}
