# 玻璃天气 GlassWeather

暗黑玻璃质感的 **Android 桌面天气小组件**（AppWidget）。

- 🖤 半透明深色玻璃卡片：顶部反光 + 细亮边，圆角 30dp
- ⛅ 显示：实时温度 / 天气 / **体感温度**（Steadman 公式本地计算）/ 最高最低 / 湿度 / 风力
- 🌙 **今夜 + 明天预报**（玻璃小卡片两行展示）
- 🕐 数据时间戳（断网显示缓存）
- 📍 默认城市：河源（`GlassWeatherWidget.java` 中 `CITY_ADCODE` 可改）
- 🔄 系统每 30 分钟 + AlarmManager 每小时兜底刷新；点卡片「↻ 刷新」手动更新
- 🌐 数据源：**高德开放平台 v3 天气接口**（Web 服务，免费，中文天气、分钟级实况）
- 📱 最低 Android 8.0 (API 26)，targetSdk 36

## 安装

从右侧 **Releases** 下载最新的 `glass-weather.apk`，安装后长按桌面 → 添加小组件 → **玻璃天气**。

> v1.0 使用 Open-Meteo（免 Key）；**v1.1+ 改用高德天气**，v1.2 增加体感/预报/重排版面，需自备高德 Web 服务 Key（免费）。
> Key 仅在构建时注入 APK，**源码仓库中不会出现真实 Key**。

## 构建（本地，无需 Gradle）

1. 把高德 Web 服务 Key 写入 `/workspace/.amap_key`（32 位，不要换行）
2. 运行 `./build.sh`（依赖 Android SDK build-tools 与 platform android.jar，脚本内可改路径）

## 高德 Key 申请

https://console.amap.com/dev/key/app → 创建应用 → 添加 Key → 服务平台选 **「Web 服务」**。

## 代码结构

```
src/com/glassweather/widget/GlassWeatherWidget.java   # 全部逻辑（数据/渲染/刷新）
res/layout/widget_weather.xml                        # 玻璃卡片布局
res/drawable/widget_bg.xml                           # 暗黑玻璃背景
build.sh                                             # 一键构建脚本（Key 注入）
```
