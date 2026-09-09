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
 * 玻璃天气 —— 暗黑玻璃质感桌面天气小组件（自动翻页展示更多数据）
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

        rv.setTextViewText(R.id.tv_emoji, blank ? "⛅" : emojiOf(w.cond));
        rv.setTextViewText(R.id.tv_temp, (blank ? "--" : w.temp) + "°");
        rv.setTextViewText(R.id.tv_cond, blank ? "加载中…" : condText(w));
        rv.setTextViewText(R.id.tv_city, (w == null || w.city == null) ? CITY_NAME : w.city);
        rv.setTextViewText(R.id.tv_date, (blank || w.dateLabel == null) ? "--月--日" : w.dateLabel);

        // 动态天气场景帧（晴/云/雨/雷/雪/雾 等, 含昼夜区分）
        if (!blank) setWeatherFrames(app, rv, w.cond);

        // 统计块
        rv.setTextViewText(R.id.tv_hi, (blank || w.hi == null) ? "--°" : w.hi + "°");
        rv.setTextViewText(R.id.tv_lo, (blank || w.lo == null) ? "--°" : w.lo + "°");
        rv.setTextViewText(R.id.tv_humidity, (blank || w.hum == null) ? "--" : w.hum);
        if (!blank && w.windDir != null) rv.setTextViewText(R.id.tv_wind_dir, "风·" + w.windDir);
        rv.setTextViewText(R.id.tv_wind, (blank || w.wind == null) ? "--" : w.wind);

        // 未来页：今夜 / 明天 / 后天
        rv.setTextViewText(R.id.tv_f1, (blank || w.nightWeather == null)
                ? "--" : emojiOf(w.nightWeather) + "  今夜  " + w.nightWeather
                + ((w.nightTemp == null) ? "" : "  " + w.nightTemp + "°"));
        rv.setTextViewText(R.id.tv_f2, (blank || w.d2Weather == null)
                ? "--" : emojiOf(w.d2Weather) + "  明天  " + w.d2Weather
                + ((w.d2Hi == null || w.d2Lo == null) ? "" : "  " + w.d2Hi + "° / " + w.d2Lo + "°"));
        rv.setTextViewText(R.id.tv_f3, (blank || w.d3Weather == null)
                ? "--" : emojiOf(w.d3Weather) + "  后天  " + w.d3Weather
                + ((w.d3Hi == null || w.d3Lo == null) ? "" : "  " + w.d3Hi + "° / " + w.d3Lo + "°"));

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

    private static String condText(Weather w) {
        StringBuilder c = new StringBuilder(w.cond == null ? "" : w.cond);
        if (w.feels != null) c.append(" · 体感 ").append(w.feels).append("°");
        return c.toString();
    }

    // ------------------------------------------------------- 动态天气场景帧

    private static final int[] WF_IDS = {
            R.id.wf0, R.id.wf1, R.id.wf2, R.id.wf3, R.id.wf4, R.id.wf5
    };

    /** 中文天气 → 场景资源前缀 */
    static String sceneKey(String cond) {
        if (cond == null) return "clear_day";
        if (cond.contains("雷")) return "thunder";
        if (cond.contains("雪")) return "snow";
        if (cond.contains("雾") || cond.contains("霾")) return "fog";
        if (cond.contains("雨")) return "rain";
        if (cond.contains("阴")) return "overcast";
        if (cond.contains("多云")) return "cloud_day";
        int h = new Date().getHours();
        boolean day = h >= 6 && h < 19;
        return day ? "clear_day" : "clear_night";
    }

    private void setWeatherFrames(Context app, RemoteViews rv, String cond) {
        String key = sceneKey(cond);
        try {
            for (int i = 0; i < WF_IDS.length; i++) {
                int res = app.getResources().getIdentifier(
                        "wx_" + key + "_" + i, "drawable", app.getPackageName());
                if (res != 0) rv.setImageViewResource(WF_IDS[i], res);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- network（高德 v3）

    private String get(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "glass-weather-widget/1.3");
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
                    JSONObject c1 = casts.optJSONObject(1);
                    w.d2Weather = num(c1.optString("dayweather", ""));
                    w.d2Hi = num(c1.optString("daytemp", ""));
                    w.d2Lo = num(c1.optString("nighttemp", ""));
                }
                if (casts.length() > 2) {
                    JSONObject c2 = casts.optJSONObject(2);
                    w.d3Weather = num(c2.optString("dayweather", ""));
                    w.d3Hi = num(c2.optString("daytemp", ""));
                    w.d3Lo = num(c2.optString("nighttemp", ""));
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
        String nightWeather, nightTemp;
        String d2Weather, d2Hi, d2Lo;   // 明天
        String d3Weather, d3Hi, d3Lo;   // 后天

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
                        .put("d2Weather", nz(d2Weather)).put("d2Hi", nz(d2Hi)).put("d2Lo", nz(d2Lo))
                        .put("d3Weather", nz(d3Weather)).put("d3Hi", nz(d3Hi)).put("d3Lo", nz(d3Lo));
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
                w.d3Weather = o.optString("d3Weather", null);
                w.d3Hi = o.optString("d3Hi", null);
                w.d3Lo = o.optString("d3Lo", null);
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
