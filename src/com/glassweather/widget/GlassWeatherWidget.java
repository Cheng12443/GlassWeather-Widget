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
import android.view.View;
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

    // ★ 高德开放平台 Web 服务 Key（构建时由 build.sh 注入，仓库内保持占位符）
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
        if (w == null) {
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
        boolean blank = (w == null || w.isBlank());

        String temp = blank ? "--" : w.temp;
        String cond = blank ? "加载中…" : w.cond;

        rv.setTextViewText(R.id.tv_emoji, blank ? "⛅" : emojiOf(w.cond));
        rv.setTextViewText(R.id.tv_temp, temp + "°");

        if (!blank) {
            StringBuilder c = new StringBuilder(cond);
            if (w.feels != null) c.append(" · 体感 ").append(w.feels).append("°");
            rv.setTextViewText(R.id.tv_cond, c.toString());
            rv.setTextViewText(R.id.tv_city, w.city != null ? w.city : CITY_NAME);
            if (w.dateLabel != null) rv.setTextViewText(R.id.tv_date, w.dateLabel);

            if (w.hi != null) rv.setTextViewText(R.id.tv_hi, w.hi + "°");
            if (w.lo != null) rv.setTextViewText(R.id.tv_lo, w.lo + "°");
            if (w.hum != null) rv.setTextViewText(R.id.tv_humidity, w.hum);
            if (w.windDir != null) rv.setTextViewText(R.id.tv_wind_dir, "风·" + w.windDir);
            if (w.wind != null) rv.setTextViewText(R.id.tv_wind, w.wind);

            // 今夜 / 明天
            StringBuilder f = new StringBuilder();
            if (w.nightWeather != null) {
                f.append("今夜 ").append(w.nightWeather);
                if (w.nightTemp != null) f.append(" ").append(w.nightTemp).append("°");
            }
            if (w.d2Weather != null) {
                if (f.length() > 0) f.append("\n");
                f.append("明天 ").append(w.d2Weather);
                if (w.d2Hi != null && w.d2Lo != null)
                    f.append(" ").append(w.d2Hi).append("° / ").append(w.d2Lo).append("°");
            }
            if (f.length() > 0) {
                rv.setViewVisibility(R.id.tv_forecast, View.VISIBLE);
                rv.setTextViewText(R.id.tv_forecast, f.toString());
            } else {
                rv.setViewVisibility(R.id.tv_forecast, View.GONE);
            }
        } else {
            rv.setTextViewText(R.id.tv_cond, cond);
            rv.setViewVisibility(R.id.tv_forecast, View.GONE);
        }

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
        conn.setRequestProperty("User-Agent", "glass-weather-widget/1.2");
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
            w.city = city.isEmpty() ? CITY_NAME : city;
            w.cond = l.optString("weather", "");
            w.temp = num(l.optString("temperature", ""));
            String hum = l.optString("humidity", "");
            w.hum = num(hum);
            if (w.hum != null && !hum.endsWith("%")) w.hum += "%";
            w.windDir = num(l.optString("winddirection", ""));
            String pow = num(l.optString("windpower", ""));
            w.wind = (pow == null) ? null : (pow.endsWith("级") ? pow : pow + "级");
            w.dataTime = hm(l.optString("reporttime", ""));

            // 体感温度（Steadman 近似公式）
            Integer fl = feelsLike(w.temp, hum, pow);
            w.feels = (fl == null) ? null : String.valueOf(fl);

            JSONArray fcs = fc.optJSONArray("forecasts");
            JSONArray casts = (fcs != null && fcs.length() > 0)
                    ? fcs.optJSONObject(0).optJSONArray("casts") : null;
            if (casts != null && casts.length() > 0) {
                JSONObject today = casts.optJSONObject(0);
                w.hi = num(today.optString("daytemp", ""));
                w.lo = num(today.optString("nighttemp", ""));
                w.nightWeather = num(today.optString("nightweather", ""));
                w.nightTemp = num(today.optString("nighttemp", ""));
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
                if (casts.length() > 1) {
                    JSONObject tmr = casts.optJSONObject(1);
                    w.d2Weather = num(tmr.optString("dayweather", ""));
                    w.d2Hi = num(tmr.optString("daytemp", ""));
                    w.d2Lo = num(tmr.optString("nighttemp", ""));
                }
            }
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

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

    /** 风力等级 → m/s（1..11 级对照） */
    private static double windMs(int level) {
        double[] t = {0, 1.6, 3.4, 5.4, 7.9, 10.7, 13.8, 17.2, 20.7, 24.5, 28.5, 32.7};
        if (level < 1) return 0;
        if (level > 11) return 35;
        return t[level];
    }

    private static int parseLevel(String powerText) {
        if (powerText == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(powerText);
        int last = 0;
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group(1));
            } catch (Exception ignored) {
            }
        }
        return last;
    }

    /** Steadman 体感温度（近似） */
    private static Integer feelsLike(String temp, String humidity, String powerText) {
        if (temp == null || humidity == null) return null;
        try {
            double T = Double.parseDouble(temp);
            double rh = Double.parseDouble(humidity.replace("%", ""));
            int lv = parseLevel(powerText);
            double ws = windMs(lv);
            double e = rh / 100.0 * 6.105 * Math.exp(17.27 * T / (237.7 + T));
            double at = T + 0.33 * e - 0.70 * ws - 4.00;
            return (int) Math.round(at);
        } catch (Exception e) {
            return null;
        }
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
        String city, cond, temp, feels, hi, lo, hum, windDir, wind, dateLabel, dataTime;
        String nightWeather, nightTemp, d2Weather, d2Hi, d2Lo;

        boolean isBlank() {
            return temp == null || temp.isEmpty();
        }

        String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("city", nz(city)).put("cond", nz(cond)).put("temp", nz(temp))
                        .put("feels", nz(feels)).put("hi", nz(hi)).put("lo", nz(lo))
                        .put("hum", nz(hum)).put("windDir", nz(windDir)).put("wind", nz(wind))
                        .put("dateLabel", nz(dateLabel)).put("dataTime", nz(dataTime))
                        .put("nightWeather", nz(nightWeather)).put("nightTemp", nz(nightTemp))
                        .put("d2Weather", nz(d2Weather)).put("d2Hi", nz(d2Hi)).put("d2Lo", nz(d2Lo));
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
                w.feels = o.optString("feels", null);
                w.hi = o.optString("hi", null);
                w.lo = o.optString("lo", null);
                w.hum = o.optString("hum", null);
                w.windDir = o.optString("windDir", null);
                w.wind = o.optString("wind", null);
                w.dateLabel = o.optString("dateLabel", null);
                w.dataTime = o.optString("dataTime", null);
                w.nightWeather = o.optString("nightWeather", null);
                w.nightTemp = o.optString("nightTemp", null);
                w.d2Weather = o.optString("d2Weather", null);
                w.d2Hi = o.optString("d2Hi", null);
                w.d2Lo = o.optString("d2Lo", null);
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
