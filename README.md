# 玻璃天气 GlassWeather

暗黑玻璃质感的 **Android 桌面天气小组件**（AppWidget）。

- 🖤 半透明深色玻璃卡片：顶部反光 + 细亮边，圆角 30dp
- ⛅ 显示：实时温度 / 天气 / 体感 / 最高最低 / 降水概率 / 湿度 / 更新时间
- 📍 默认城市：河源（`src/.../GlassWeatherWidget.java` 中 `HOME_LAT/HOME_LON` 可改）
- 🔄 每小时自动刷新，点卡片「↻ 刷新」手动更新
- 🌐 数据源：Open-Meteo（免 API Key）
- 📱 最低 Android 8.0 (API 26)，targetSdk 36

## 安装
从右侧 Releases 下载 `glass-weather.apk`，安装后长按桌面 → 添加小组件 → **玻璃天气**。

## 构建
无需 Gradle，直接运行 `./build.sh`（依赖本机 Android SDK build-tools + platform android.jar，脚本里有对应环境变量）。
