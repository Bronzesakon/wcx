# 欢迎

## 二次开发衍生声明

本 WCX 模块基于上游开源项目 WeKit 进行 Fork 二次修改与功能拓展，属于衍生二改项目。

上游原项目仓库：https://github.com/Ujhhgtg/WeKit

上游所有代码、UI、逻辑版权归属原开源作者，本项目完整遵循上游开源协议。上游相关介绍、致谢、依赖内容全部保留不作删减。

---

WCX 是一个功能丰富的微信增强模块，通过 Xposed 框架（推荐 LSPosed）加载，提供 **200+ 个功能开关，分为 20 余个分类**，全部可以在设置里按分类单独打开或关闭。

[![CI 状态](https://github.com/Johnny520/wcx/actions/workflows/ci.yml/badge.svg)](https://github.com/Johnny520/wcx/actions/workflows/ci.yml)

## 导航

* [🚀 快速开始](/wcx-docs/getting-started.md)
* [📥 安装指南](/wcx-docs/installation.md)
* [⚙️ 配置指南](/wcx-docs/configuration.md)
* [🔧 模块设置说明](/wcx-docs/module-settings.md)
* [❓ 常见问题](/wcx-docs/faq.md)
* [🛠 开发指南](/wcx-docs/development.md)

## 主要能力

* **聊天**：防撤回（新版徽标 / 旧版系统提示两种样式）、AI 与关键词自动回复、对话分组与归拢、定时发送、群发、消息过滤屏蔽、Markdown 渲染、悬浮标题栏与输入框、图片 / 文件 / 语音保存本地
* **界面美化**：主题商城、四 Tab 主题背景、莫奈引擎、微信主页侧边栏、自定义消息气泡、底部导航栏、主页 FAB
* **联系人与群组**：隐藏联系人（会话 / 通讯录 / 搜索 / 朋友圈 / 通话等隐藏面）、单向删除好友检测、自动同意好友申请、群成员实名与身份显示
* **朋友圈**：朋友圈与评论防撤回、自动点赞、一键转发、拦截广告、伪集赞
* **红包与支付**：自动抢红包、抢红包金额统计、捡漏历史红包、指纹支付、自动接收转账
* **批量操作**：批量删好友 / 删记录 / 打标签 / 免打扰 / 隐藏对话、群发消息、导出聊天记录
* **系统与隐私**：反 Xposed 检测、Native 反检测、环境伪装、虚拟定位、修改步数、强制平板模式、DPI 修改
* **脚本插件**：独立「脚本」Tab、本地插件管理与在线插件市场、插件 Agent、JS 与 Java 双引擎、MCP & REST API 服务器
* **稳定性**：模块自检、脚本功能诊断与一键修复、「上次退出原因」、分级日志、在线特征包

## 修改内容 (相比 [上游](https://github.com/cwuom/WeKit))

* 添加 WAuxiliary 与 NewMiko 目前公开源代码中的部分功能
* 移除全部校验，减少模块体积，避免不必要的性能开销
* 移植 UI 至 Jetpack Compose
* 添加、修复、增强若干闭源模块的部分功能
* 移植其他模块的一些功能
* 反射层与原生库按本项目需要重构
* 支持 arm64-v8a / armeabi-v7a 两种 ABI
* 无须禁用「Xposed API 调用保护」
* 修复问题，新增大量功能

## WCX 二次开发新增功能

* 防撤回重构：徽标样式与旧版系统提示双模式，可互相切换并迁移历史提示
* 群成员变动提醒（入群 / 退群 / 改昵称 / 被踢监控，支持本地观察与群广播模式）
* AI 自动回复增强（私聊黑白名单、调试日志、API 兼容性优化）
* 对话分组与对话归拢（自定义分组、文件夹归拢、实时刷新）
* 美化功能增强（四 Tab 主题背景、沉浸式全屏渲染、主题导入导出与主题商城）
* 红包功能增强（链路提速、企业微信互通群适配、私聊群聊分离延迟、金额统计）
* 自动同意好友申请（支持延迟、欢迎语、黑名单）
* 关键词自动回复（私聊 / 群聊独立规则、冷却机制）
* 消息类型过滤屏蔽（模板管理、黑白名单、拦截日志）
* 批量操作（批量删除好友 / 聊天记录、批量打标签、批量免打扰、群发消息、导出聊天记录）
* 隐藏联系人体系（原密友能力重写：会话、通讯录、搜索、朋友圈、通话、未读角标等隐藏面）
* 脚本插件中心（本地插件、在线插件市场、插件 Agent、一键诊断与修复）
* 模块自检与稳定性工具（微信 API 层自检、DexKit 任务状态、上次退出原因、在线特征包）
* 多处 BUG 修复与性能优化

> 兼容性说明：模块目前使用 LibXposed API 101（`targetApiVersion=101`）。项目内仍有部分代码使用旧版 `de.robv.android.xposed` API，因此不切换到 102；如需使用 102 的模块请勿混用。

## 联系

[GitHub 仓库](https://github.com/Johnny520/wcx)

[Telegram 超级群组](https://t.me/+7j5dJ6g16B43OWVl)

[❤️ 爱发电赞助](https://afdian.com/a/Johnny520)

## 致谢

[WeKit 上游](https://github.com/cwuom/WeKit)

[WeKit 二改上游](https://github.com/Ujhhgtg/WeKit)

[WAuxiliary](https://github.com/HdShare/WAuxiliary_Public)

[NewMiko](https://github.com/Ujhhgtg/NewMiko/)

[QAuxiliary](https://github.com/cinit/QAuxiliary)

[FingerprintPay](https://github.com/eritpchy/FingerprintPay)

[WADN](https://github.com/Ujhhgtg/wauxv_deobf_new) [WAD](https://github.com/Ujhhgtg/wauxv_deobf)

[FunBox](https://github.com/Ujhhgtg/funbox_deobf)

[LSPlant](https://github.com/LSPosed/LSPlant)

---
