# 系统与隐私

系统功能、隐私保护、环境伪装等。

## 功能列表

- DPI 修改 — 自定义微信屏幕密度
- [二维码扫描记录](qr-code-record.md) — 记录扫描的二维码 URL
- [修改运动步数](modify-sports-step-count.md) — 修改微信获取到的或手动上传运动步数
- [去除文章广告](remove-article-ads.md) — 清除文章中的广告数据
- [强制启用 WebView 菜单](enable-webview-features.md) — 强制显示 WebView 页面右上角菜单按钮
- [强制平板模式](force-tablet-mode.md) — 让微信将当前设备识别为平板
- [恢复旧版「我」界面卡包](use-legacy-wallet-view-in-me-page.md) — 在「我」界面使用旧版「卡包」而非「小店与卡包」
- [清理缓存垃圾](auto-clean-cache.md) — 自动或手动清理微信的缓存
- [灰度测试管理器](feature-flag-manager.md) — 覆盖微信灰度测试 (Feature Flag) 的值
- [环境伪装](spoof-environment.md) — 伪装未启用 ADB, 开发者选项或 VPN, 可能有助于通过人脸等场景下的环境安全性检测
- [省电模式](power-saver.md) — 通过一些措施, 减少微信耗电量
- [禁止屏幕高亮度](disable-high-brightness.md) — 禁止微信将屏幕亮度设置得过高
- [禁止微信检测 Xposed](prevent-xposed-detection.md) — 防止微信检测 Xposed 框架是否存在
- [禁用 WebView 安全警告](disable-webview-safety-warnings.md) — 禁用 WebView 相关的安全警告提示
- 禁用「刚刚在看」提醒 — 禁用微信启动时弹出的「刚刚在看的「视频号」」继续浏览提示条
- [禁用「转发截图」提示](disable-share-screenshot-toast.md) — 你在教我做事?
- [禁用存储空间不足检测](disable-low-available-storage-detection.md) — 「隐藏应用列表」等隐藏 Root 模块有时会使应用获取到的可用空间不正确, 而微信在可用空间不足时会强制要求清理空间才可继续使用, 本功能移除了该限制
- [禁用微信热更新](disable-host-hot-updates.md) — 禁止微信热更新, 避免被强制更新到不兼容版本
- 禁用微信进程状态检测器 — 微信会在后台每隔一段时间检测微信主进程状态, 并在特定条件下故意抛出异常结束主进程 虽然我不知道这玩意有啥用和是否应该关掉, 但「崩溃拦截」会把这玩意算进去, 有点烦, 所以如果你想关的话这里可以关
- [移除二维码扫描限制](remove-qr-code-scan-limit.md) — 移除长按图片与相册选择的二维码扫描限制
- [移除分享签名校验](remove-external-app-sharing-signature-verify.md) — 移除第三方应用分享到微信的签名校验
- [自动批准设备登录](auto-approve-device-login.md) — 其他设备请求登录时自动勾选选项并点击按钮
- [虚拟定位](fake-location.md) — 预设定微信获取到的经纬度
- [链接跳转系统打开方式](link-external-app-jump.md) — 打开链接或卡片链接时显示对话框, 可直接使用系统打开方式打开 若要跳转到第三方应用, 需先在对应应用设置中启用「在此应用中打开支持的网页链接」
- 阻止 Native 检测 — 在 Native 层拦截 /proc/self/maps 扫描、链接库枚举、模块安装探测与反调试, 防止微信通过绕过 Java Hook 的方式检测本模块与 Hook 框架。需重启微信后生效 (实验性)
- [阻止微信清理模块数据](prevent-module-data-deletion.md) — 阻止微信「设置 → 存储空间 → 清理」删除模块数据
- [隐藏模块应用](hide-module-from-app-list.md) — 在不影响模块功能的情况下防止微信查询模块安装状态 (实验性)
- [预见性返回动画](predictive-back-gestures.md) — 为微信的活动强制启用预见性返回动画 需系统 Android SDK >= 33
