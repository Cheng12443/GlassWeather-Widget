package com.glassweather.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 玻璃天气 —— 暗黑玻璃质感桌面天气小组件
 * 数据源：高德开放平台 v3 天气接口（Web 服务，免费，需 Key）
 */
public class GlassWeatherWidget extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "com.glassweather.widget.ACTION_REFRESH";
    private static final long PERIOD = 60L * 60 * 1000;          // 自调度周期：1 小时
    private static final String PREFS = "gw_cache";
    private static final String KEY_DATA = "data";

    // ★ 高德开放平台 Web 服务 Key（https://console.amap.com 应用管理 → 创建应用 → 添加 Key → Web服务）
    private static final String AMAP_KEY = "PASTE_YOUR_AMAP_KEY_HERE";
    // ★ 城市行政区划代码 adcode（默认：广东河源 441600）
    private static final String CITY_ADCODE = "441600";
    private static final String CITY_NAME = "河源";

    private static final String URL_LIVE = "https://restapi.amap.com/v3/weather/weatherInfo?"
            + "city=" + CITY_ADCODE + "&extensions=base&key=" + AMAP_KEY;
    private static final String URL_FORECAST = "https://restapi.amap.com/v3/weather/weatherInfo?"
            + "city=" + CITY_ADCODE + "&extensions=all&key=" + AMAP_KEY;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) {
            scheduleNext(context);
            updateAll(context);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        scheduleNext(context);
        updateAll(context);
    }

    @Override
    public void onEnabled(Context context) {
        scheduleNext(context);
    }

    @Override
    public void onDisabled(Context context) {
        cancelAlarm(context);
    }

    private void scheduleNext(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, GlassWeatherWidget.class).setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(context, 7, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long trigger = SystemClock.elapsedRealtime() + PERIOD;
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            }
        } catch (Exception ignored) {
        }
    }

    private void cancelAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, GlassWeatherWidget.class).setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(context, 7, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }

    // ---------------------------------------------------------------- update

    private void updateAll(Context context) {
        final Context app = context.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                fetchAndPush(app);
            }
        }, "glass-weather");
        t.setDaemon(true);
        t.start();
    }

    private void fetchAndPush(Context app) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(app);
        ComponentName cn = new ComponentName(app, GlassWeatherWidget.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null || ids.length == 0) return;

        Weather fresh = fetchWeather();
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        Weather w = fresh;
        boolean stale = false;
        if (w == null) {                       // 网络失败 → 读缓存
            w = Weather.fromJson(sp.getString(KEY_DATA, null));
            stale = w != null && !w.isBlank();
        } else {
            sp.edit().putString(KEY_DATA, w.toJson()).apply();
        }

        String now = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        for (int id : ids) {
            try {
                RemoteViews rv = buildViews(app, w, now, stale);
                mgr.updateAppWidget(id, rv);
            } catch (Exception ignored) {
            }
        }
    }

    private RemoteViews buildViews(Context app, Weather w, String now, boolean stale) {
        RemoteViews rv = new RemoteViews(app.getPackageName(), R.layout.widget_weather);

        String temp = (w == null || w.temp == null) ? "--" : w.temp;
        String cond = (w == null || w.cond == null) ? "加载中…" : w.cond;

        rv.setTextViewText(R.id.tv_emoji, (w == null || w.isBlank()) ? "⛅" : emojiOf(w.cond));
        rv.setTextViewText(R.id.tv_temp, temp + "°");
        rv.setTextViewText(R.id.tv_cond, cond);
        rv.setTextViewText(R.id.tv_city, (w == null || w.city == null) ? CITY_NAME : w.city);

        if (w != null && !w.isBlank()) {
            if (w.hi != null)  rv.setTextViewText(R.id.tv_hi, "最高 " + w.hi + "°");
            if (w.lo != null)  rv.setTextViewText(R.id.tv_lo, "最低 " + w.lo + "°");
            if (w.hum != null) rv.setTextViewText(R.id.tv_humidity, "湿度 " + w.hum);
            if (w.wind != null) rv.setTextViewText(R.id.tv_wind, "风 " + w.wind);
            if (w.dateLabel != null) rv.setTextViewText(R.id.tv_date, w.dateLabel);
        }

        // 显示“数据时间”（数据源实际生成时刻），断网时显示缓存时间
        String tag = (w != null && w.dataTime != null) ? "数据 " + w.dataTime : "更新于 " + now;
        if (stale) tag += " · 缓存";
        rv.setTextViewText(R.id.tv_updated, tag);

        Intent it = new Intent(app, GlassWeatherWidget.class).setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(app, 5, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.tv_refresh, pi);
        rv.setOnClickPendingIntent(R.id.root, pi);
        return rv;
    }

    // ---------------------------------------------------------------- network（高德 v3）

    private String get(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "glass-weather-widget/1.1");
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            return null;
        }
        InputStream in = conn.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        conn.disconnect();
        return sb.toString();
    }

    private Weather fetchWeather() {
        try {
            String liveJson = get(URL_LIVE);
            String fcJson = get(URL_FORECAST);
            if (liveJson == null || fcJson == null) return null;

            Weather w = new Weather();
            JSONObject live = new JSONObject(liveJson);
            JSONObject fc = new JSONObject(fcJson);
            if (!"1".equals(live.optString("status")) || !"1".equals(fc.optString("status"))) return null;

            JSONObject l = live.getJSONArray("lives").optJSONObject(0);
            if (l == null) return null;
            String city = l.optString("city", "");
            w.city = (city.isEmpty()) ? CITY_NAME : city;
            w.cond = l.optString("weather", "");
            w.temp = num(l.optString("temperature", ""));
            String hum = l.optString("humidity", "");
            w.hum = num(hum);
            if (w.hum != null && !hum.endsWith("%") && !hum.isEmpty()) w.hum += "%";
            String dir = l.optString("winddirection", "");
            String pow = l.optString("windpower", "").replace("级", "");
            w.wind = dir + "风" + pow + "级";
            w.dataTime = hm(l.optString("reporttime", ""));

            JSONArray casts = fc.optJSONObject("forecasts")
                    .optJSONArray("casts");
            if (casts != null && casts.length() > 0) {
                JSONObject today = casts.optJSONObject(0);
                w.hi = num(today.optString("daytemp", ""));
                w.lo = num(today.optString("nighttemp", ""));
                String date = today.optString("date", "");
                if (!date.isEmpty()) {
                    try {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                        SimpleDateFormat f = new SimpleDateFormat("M月d日 EEE", Locale.CHINA);
                        w.dateLabel = f.format(sdf.parse(date));
                    } catch (Exception e) {
                        w.dateLabel = date;
                    }
                }
            }
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    private static String num(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || "暂无".equals(t)) return null;
        return t;
    }

    private static String hm(String reportTime) {
        if (reportTime == null || reportTime.length() < 16) return null;
        return reportTime.substring(11, 16);
    }

    /** 中文天气描述 → emoji */
    private static String emojiOf(String zh) {
        if (zh == null) return "⛅";
        if (zh.contains("雷")) return "⛈️";
        if (zh.contains("雪")) return "❄️";
        if (zh.contains("雾") || zh.contains("霾")) return "🌫️";
        if (zh.contains("暴")) return "⛈️";
        if (zh.contains("雨")) return "🌧️";
        if (zh.contains("阴")) return "☁️";
        if (zh.contains("晴间多云")) return "🌤️";
        if (zh.contains("多云")) return "⛅";
        if (zh.contains("晴")) return "☀️";
        return "⛅";
    }

    // ---------------------------------------------------------------- model

    static class Weather {
        String city, cond, temp, hi, lo, hum, wind, dateLabel, dataTime;

        boolean isBlank() {
            return temp == null || temp.isEmpty();
        }

        String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("city", nz(city)).put("cond", nz(cond)).put("temp", nz(temp))
                        .put("hi", nz(hi)).put("lo", nz(lo)).put("hum", nz(hum))
                        .put("wind", nz(wind)).put("dateLabel", nz(dateLabel))
                        .put("dataTime", nz(dataTime));
                return o.toString();
            } catch (Exception e) {
                return null;
            }
        }

        static Weather fromJson(String s) {
            if (s == null) return null;
            try {
                JSONObject o = new JSONObject(s);
                Weather w = new Weather();
                w.city = o.optString("city", null);
                w.cond = o.optString("cond", null);
                w.temp = o.optString("temp", null);
                w.hi = o.optString("hi", null);
                w.lo = o.optString("lo", null);
                w.hum = o.optString("hum", null);
                w.wind = o.optString("wind", null);
                w.dateLabel = o.optString("dateLabel", null);
                w.dataTime = o.optString("dataTime", null);
                return w;
            } catch (Exception e) {
                return null;
            }
        }

        private static String nz(String v) {
            return v == null ? "" : v;
        }
    }
}
