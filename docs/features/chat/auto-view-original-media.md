# 自动查看原图/原视频

> 打开聊天里的图片 / 视频时**无感**加载原画质：不显示「查看原图 / 查看原视频」按钮，自动拉取原图 / 原视频

## 类别

聊天

## 类型

可开关

## 描述

在聊天中打开图片或视频时，模块会在**绘制之前**拦下微信的「查看原图 / 查看原视频」按钮
（先隐藏、再触发），用户看不到任何按钮，画面直接进入原画质加载流程。

视频的原始文件可能较大（例如 64MB），若使用流量请留意。

## 使用方法

在模块设置中启用

## 实现说明

微信图片 / 视频查看页 (`com.tencent.mm.ui.chatting.gallery.ImageGalleryUI`) 的
「查看原图 / 查看原视频」按钮，运行时是 `android.widget.Button`，文案来自字符串资源
（中文「查看原图」「查看原视频」，英文 View Full Video / Full Image），并带体积后缀
（「查看原视频 64MB」），所以只能**按渲染文本**定位，无法按字段类型或 dex 字符串定位。

实现分三层：

1. **帧级拦截（核心）**：在该页 `decorView` 上挂 `ViewTreeObserver.OnPreDrawListener`，
   在每一帧 `draw` 之前扫描视图树。命中关键字后**先 `setVisibility(GONE)` 再
   `performClick()`** —— 本帧的 `dispatchDraw` 会直接跳过这个按钮，用户一帧都看不到它，
   视觉上就是「打开图片直接清晰 / 打开视频直接原画」。
2. **持续压制**：微信在下载原图 / 原视频期间会重新显示按钮（文案变成进度），
   已处理过的控件会被记录，之后只要再次出现就压回 GONE，整个加载过程没有「查看原图」这一环。
   去重按「控件身份 + 文案」：页内左右滑动切到下一个视频时文案会变（设备日志里同一控件
   对应 64MB / 19MB / 2MB 三个视频各自触发过一次），因此仍会正常触发，又不会重复点击同一个按钮。
3. **触发入口与兜底**：`onCreate` / `onResume` hook 起观察（类名未混淆、自身声明了这两个方法），
   原有两个 dex 方法（`setImageHdImgBtnVisibility` / `checkNeedShowOriginVideoBtn`）仍 hook
   作为状态更新信号；另有 500ms 兜底轮询，防「页面静止不重绘」时漏掉按钮。
   观察窗口 5 分钟并随 onResume 续期（页内滑动不重走 onResume），Activity 销毁即停止。

反查安装包（微信 8.0.76.3141）得到的两个结论：

- 旧实现依赖的 `setImageHdImgBtnVisibility` 在 dex 里**根本不存在**；
  `checkNeedShowOriginVideoBtn` 只是一条日志文案（`...: msg is expired`），不是稳定的可 hook 方法名；
- 点击这两个按钮**不会弹网络确认框**（资源里没有「非 WLAN」类文案），所以静默自动加载不会打断用户。

日志 tag `AutoViewOriginalMedia`：启动开关判定、观察器启动、每次命中都会打印，
可用于定位「开关没开 / 生命周期 hook 没装上 / 视图树没找到按钮」。
