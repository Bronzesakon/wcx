# 调试

调试功能用于排查问题、分析性能和崩溃诊断。

## 功能列表

- [内存分析](profile-memory.md) — 分析微信内存占用组成
- [发包调试](send-packet.md) — 发送自定义数据包到微信服务器
- [启动微信内部 URL](launch-internal-urls.md) — 跳转微信 weixin:// URL
- [复制调试信息](copy-wechat-debug-info.md) — 在报告模块问题时, 请附上本功能的结果
- [崩溃拦截](crash-interceptor.md) — 拦截 Java 层崩溃并记录详细信息, 支持查看和导出日志
- [崩溃拦截 (Native)](native-crash-interceptor.md) — 拦截 Native 层崩溃并记录详细信息，支持查看和导出日志
- [测试](experiments.md) — 手动对指定 wxId 依次执行 beforetransfer 和 transferplaceorder 两个阶段的发包请求，并显示原始返回值
- [测试崩溃](trigger-crash.md) — 没事别点
- [重定向微信日志](redirect-host-logs.md) — 将微信内部日志打印至模块日志
- [重置适配信息](reset-dex-cache.md) — 清除全部 DEX 适配信息, 等待下次启动时重新适配
