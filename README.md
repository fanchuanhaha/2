# WidgetFlow

安卓桌面小组件数据刷新工具：把任意 HTTP API（JSON / 纯文本）的数据通过表达式抽取后，展示到桌面小组件上。

## 功能

- 支持 GET / POST 请求、自定义请求头、参数与请求体
- 响应解析三种方式：
  - **JSON**：JSONPath 取值，如 `data.title`
  - **正则**：正则表达式首匹配（优先第 1 捕获组）
  - **文本**：整段文本响应
- 元素模板占位符 `{别名}`、`{time}`，支持字号 / 颜色 / 绝对定位
- 自动刷新（30 分钟 ~ 每日），失败自动重试 2 次
- 2x2 与 4x2 两种桌面小组件尺寸
- 深色模式适配
- 全局崩溃日志与关键路径异常捕获，App 内可查看 / 分享日志，方便排查问题
- 配置一键导入导出（wfw/1 格式）

## 截图预览

在应用首页通过「查看日志」可读取 `filesDir/logs/app.log` 与 `crash.txt` 排查问题。

## 构建

```bash
# 需要 JDK 17 与 Android SDK
gradle :app:assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

## 签名

仓库不包含签名文件。构建时若缺少 `keystore.properties`，将回退使用 debug 签名。

## 版本

- 版本名: 0.1.0
- minSdk 26 / targetSdk 34
