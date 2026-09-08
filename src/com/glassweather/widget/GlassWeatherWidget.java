package com.glassweather.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.RemoteViews;

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
 * 数据源：Open-Meteo（无需 API Key）
 */
public class GlassWeatherWidget extends AppWidgetProvider {

    public static final String ACTION_REFRESH = "com.glassweather.widget.ACTION_REFRESH";
    private static final long PERIOD = 60L * 60 * 1000;          // 自调度周期：1 小时
    private static final String PREFS = "gw_cache";
    private static final String KEY_JSON = "json";

    // 默认城市：广东河源（可改经纬度）
    private static final double HOME_LAT = 23.7331;
    private static final double HOME_LON = 114.6830;
    private static final String CITY_NAME = "河源";

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

        String freshJson = fetch();
        SharedPreferences sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        if (freshJson != null) {
            sp.edit().putString(KEY_JSON, freshJson).apply();
        }
        String json = freshJson != null ? freshJson : sp.getString(KEY_JSON, null);

        Weather w = (json != null) ? Weather.parse(json) : null;
        if (w == null && freshJson != null) {
            // 数据能取到但解析失败：退化为空对象，只改更新时间
            w = new Weather();
        }

        String updated = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        boolean stale = freshJson == null && w != null && !w.isBlank();

        for (int id : ids) {
            try {
                RemoteViews rv = buildViews(app, w, updated, stale);
                mgr.updateAppWidget(id, rv);
            } catch (Exception ignored) {
            }
        }
    }

    private RemoteViews buildViews(Context app, Weather w, String updated, boolean stale) {
        RemoteViews rv = new RemoteViews(app.getPackageName(), R.layout.widget_weather);
        rv.setEmptyView(R.id.root, R.id.root);

        // 默认值兜底
        String emoji = "⛅", cond = w.isBlank() ? "加载中…" : (w.cond == null ? "" : w.cond);
        String temp = w.isBlank() ? "--°" : w.temp + "°";

        if (!w.isBlank()) {
            emoji = emojiFor(w.code, w.isDay);
            if (w.feels != null) cond = cond + " · 体感 " + w.feels + "°";
        }
        rv.setTextViewText(R.id.tv_emoji, emoji);
        rv.setTextViewText(R.id.tv_temp, temp);
        rv.setTextViewText(R.id.tv_cond, cond);
        rv.setTextViewText(R.id.tv_city, CITY_NAME);

        if (!w.isBlank()) {
            rv.setTextViewText(R.id.tv_date, w.dateLabel);
            rv.setTextViewText(R.id.tv_hi, "最高 " + w.hi + "°");
            rv.setTextViewText(R.id.tv_lo, "最低 " + w.lo + "°");
            rv.setTextViewText(R.id.tv_precip, w.precip != null ? "降水 " + w.precip + "%" : "降水 --");
            rv.setTextViewText(R.id.tv_hum, w.hum != null ? "湿度 " + w.hum + "%" : "湿度 --");
        }

        rv.setTextViewText(R.id.tv_updated,
                stale ? "更新于 " + updated + " · 离线缓存" : "更新于 " + updated);

        Intent it = new Intent(app, GlassWeatherWidget.class).setAction(ACTION_REFRESH);
        PendingIntent pi = PendingIntent.getBroadcast(app, 5, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.tv_refresh, pi);
        rv.setOnClickPendingIntent(R.id.root, pi);
        return rv;
    }

    // ---------------------------------------------------------------- network

    private String fetch() {
        try {
            StringBuilder url = new StringBuilder("https://api.open-meteo.com/v1/forecast?");
            url.append("latitude=").append(HOME_LAT);
            url.append("&longitude=").append(HOME_LON);
            url.append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,is_day");
            url.append("&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max");
            url.append("&timezone=Asia%2FShanghai&forecast_days=1");

            HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("User-Agent", "glass-weather-widget/1.0");
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
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String emojiFor(int code, boolean day) {
        switch (code) {
            case 0:
                return day ? "☀️" : "🌙";
            case 1:
                return day ? "🌤️" : "🌙";
            case 2:
                return "⛅";
            case 3:
                return "☁️";
            case 45:
            case 48:
                return "🌫️";
            case 51:
            case 53:
            case 55:
            case 56:
            case 57:
                return "🌦️";
            case 61:
            case 63:
            case 65:
            case 66:
            case 67:
            case 80:
                return "🌧️";
            case 71:
            case 73:
            case 75:
            case 77:
            case 85:
            case 86:
                return "❄️";
            case 81:
            case 82:
            case 95:
            case 96:
            case 99:
                return "⛈️";
            default:
                return "⛅";
        }
    }

    /** Open-Meteo WMO 天气代码 → 中文描述 */
    private static String descFor(int code) {
        switch (code) {
            case 0:
                return "晴";
            case 1:
                return "晴间多云";
            case 2:
                return "多云";
            case 3:
                return "阴";
            case 45:
            case 48:
                return "雾";
            case 51:
            case 53:
            case 55:
            case 56:
            case 57:
                return "毛毛雨";
            case 61:
            case 63:
                return "雨";
            case 65:
                return "大雨";
            case 66:
            case 67:
                return "冻雨";
            case 71:
            case 73:
            case 75:
            case 77:
                return "雪";
            case 80:
                return "阵雨";
            case 81:
                return "中阵雨";
            case 82:
                return "强阵雨";
            case 85:
            case 86:
                return "阵雪";
            case 95:
                return "雷阵雨";
            case 96:
            case 99:
                return "雷暴冰雹";
            default:
                return "未知";
        }
    }

    /** 数据模型（解析自 Open-Meteo） */
    static class Weather {
        int code = -1;
        String cond, temp, feels, hi, lo, precip, hum, dateLabel;
        boolean isDay = true;

        boolean isBlank() {
            return temp == null;
        }

        static Weather parse(String raw) {
            try {
                Weather w = new Weather();
                JSONObject o = new JSONObject(raw);
                JSONObject cur = o.optJSONObject("current");
                if (cur == null) return w;

                w.temp = str(cur.opt("temperature_2m"));
                w.feels = str(cur.opt("apparent_temperature"));
                w.hum = str(cur.opt("relative_humidity_2m"));
                int ic = cur.optInt("weather_code", -1);
                w.code = ic;
                w.isDay = cur.optInt("is_day", 1) == 1;
                if (ic >= 0) w.cond = descFor(ic);

                JSONObject daily = o.optJSONObject("daily");
                if (daily != null) {
                    w.hi = arr(daily, "temperature_2m_max", 0);
                    w.lo = arr(daily, "temperature_2m_min", 0);
                    w.precip = arr(daily, "precipitation_probability_max", 0);
                    String date = arr(daily, "time", 0);
                    if (date != null && !"null".equals(date)) {
                        try {
                            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
                            java.util.TimeZone tz = java.util.TimeZone.getTimeZone("Asia/Shanghai");
                            sdf.setTimeZone(tz);
                            Date d = sdf.parse(date);
                            SimpleDateFormat f = new SimpleDateFormat("M月d日 EEE", Locale.CHINA);
                            f.setTimeZone(tz);
                            w.dateLabel = f.format(d);
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

        private static String str(Object v) {
            if (v == null || v == JSONObject.NULL) return null;
            String s = v.toString();
            if ("null".equals(s) || s.isEmpty()) return null;
            return s;
        }

        /** 取 daily 数组第 index 项（可能为 null / JSONObject.NULL） */
        private static String arr(JSONObject daily, String key, int index) {
            org.json.JSONArray a = daily.optJSONArray(key);
            if (a == null || a.length() <= index) return null;
            return str(a.opt(index));
        }
    }
}
