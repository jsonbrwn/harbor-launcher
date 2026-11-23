package dev.harbor.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * A small translucent weather card: current temperature and conditions, plus a 3-day outlook.
 * Data comes from Open-Meteo (free, no API key). Location is detected from the TV's IP address
 * unless a city or ZIP has been set. The last result is cached so the card shows up instantly.
 */
class WeatherWidget extends LinearLayout {

    private static final String K_CACHE = "weather_json", K_CACHE_AT = "weather_at";
    static final String K_PLACE = "weather_place";          // user-entered city/ZIP ("" = auto)
    private static final String K_LAT = "weather_lat", K_LON = "weather_lon", K_CITY = "weather_city";
    private static final long REFRESH_MS = 30 * 60 * 1000L;

    private final SharedPreferences prefs;
    private final Typeface display;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeatherIcon icon;
    private final TextView temp, cond, city;
    private final LinearLayout days;
    private boolean running;

    private final Runnable tick = new Runnable() {
        public void run() {
            refresh(false);
            if (running) main.postDelayed(this, REFRESH_MS);
        }
    };

    WeatherWidget(Context c, SharedPreferences prefs, Typeface display) {
        super(c);
        this.prefs = prefs;
        this.display = display;
        setOrientation(VERTICAL);
        setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        setBackground(Ui.frosted(c, 0x33FFFFFF, 0x4DFFFFFF, 16));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        icon = new WeatherIcon(c);
        top.addView(icon, new LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        temp = text(c, 28, 0xFFFFFFFF, display);
        temp.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 10), 0);
        top.addView(temp);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        cond = text(c, 13, 0xF2FFFFFF, null);
        city = text(c, 11, 0xB3FFFFFF, null);
        col.addView(cond);
        col.addView(city);
        top.addView(col);
        addView(top);

        days = new LinearLayout(c);
        days.setOrientation(HORIZONTAL);
        LayoutParams dl = new LayoutParams(-1, -2);
        dl.topMargin = Ui.dp(c, 8);
        addView(days, dl);

        setVisibility(INVISIBLE);
        if (!prefs.contains(K_PLACE)) {
            // Default: Jason's ZIP 85224 (Chandler, AZ), so no lookup is needed on first run.
            prefs.edit().putString(K_PLACE, "85224")
                    .putLong(K_LAT, Double.doubleToLongBits(33.3301))
                    .putLong(K_LON, Double.doubleToLongBits(-111.8826))
                    .putString(K_CITY, "Chandler").apply();
        }
        String cached = prefs.getString(K_CACHE, null);
        if (cached != null) {
            try { render(new JSONObject(cached), prefs.getString(K_CITY, "")); } catch (Exception ignored) { }
        }
    }

    private static TextView text(Context c, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        if (tf != null) t.setTypeface(tf);
        t.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 1), 0x55000000);
        t.setSingleLine(true);
        return t;
    }

    void start() {
        if (running) return;
        running = true;
        long age = System.currentTimeMillis() - prefs.getLong(K_CACHE_AT, 0);
        main.removeCallbacks(tick);
        main.postDelayed(tick, age > REFRESH_MS ? 500 : REFRESH_MS - age);
    }

    void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    /** Re-resolves the location (e.g. after the city/ZIP changes) and fetches fresh weather. */
    void refresh(final boolean relocate) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String place = prefs.getString(K_PLACE, "");
                    double lat = Double.longBitsToDouble(prefs.getLong(K_LAT, Double.doubleToLongBits(Double.NaN)));
                    double lon = Double.longBitsToDouble(prefs.getLong(K_LON, Double.doubleToLongBits(Double.NaN)));
                    String cityName = prefs.getString(K_CITY, "");
                    if (relocate || Double.isNaN(lat)) {
                        double[] ll = new double[2];
                        String[] name = new String[1];
                        boolean ok = place.length() > 0 ? geocode(place, ll, name) : ipLocate(ll, name);
                        if (!ok) return;
                        lat = ll[0]; lon = ll[1]; cityName = name[0];
                        prefs.edit().putLong(K_LAT, Double.doubleToLongBits(lat))
                                .putLong(K_LON, Double.doubleToLongBits(lon))
                                .putString(K_CITY, cityName).apply();
                    }
                    String url = String.format(Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                                    + "&current=temperature_2m,weather_code,is_day"
                                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                                    + "&temperature_unit=fahrenheit&timezone=auto&forecast_days=4", lat, lon);
                    final JSONObject json = new JSONObject(get(url));
                    final String cn = cityName;
                    prefs.edit().putString(K_CACHE, json.toString()).putLong(K_CACHE_AT, System.currentTimeMillis()).apply();
                    main.post(new Runnable() { public void run() { render(json, cn); } });
                } catch (Exception e) {
                    android.util.Log.w("Harbor", "weather: " + e);
                }
            }
        }, "harbor-weather").start();
    }

    private void render(JSONObject json, String cityName) {
        try {
            JSONObject cur = json.getJSONObject("current");
            int code = cur.getInt("weather_code");
            icon.set(code, cur.optInt("is_day", 1) == 1);
            temp.setText(Math.round(cur.getDouble("temperature_2m")) + "°");
            cond.setText(WeatherIcon.describe(code));
            city.setText(cityName);

            JSONObject d = json.getJSONObject("daily");
            JSONArray t = d.getJSONArray("time"), wc = d.getJSONArray("weather_code");
            JSONArray hi = d.getJSONArray("temperature_2m_max"), lo = d.getJSONArray("temperature_2m_min");
            days.removeAllViews();
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.US);
            for (int i = 0; i < t.length() && i < 4; i++) {
                LinearLayout col = new LinearLayout(getContext());
                col.setOrientation(VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                String label = i == 0 ? "TODAY" : out.format(in.parse(t.getString(i))).toUpperCase(Locale.US);
                TextView dn = text(getContext(), 9, 0xB3FFFFFF, display);
                dn.setText(label);
                col.addView(dn);
                WeatherIcon wi = new WeatherIcon(getContext());
                wi.set(wc.getInt(i), true);
                col.addView(wi, new LayoutParams(Ui.dp(getContext(), 22), Ui.dp(getContext(), 22)));
                TextView hl = text(getContext(), 11, 0xF2FFFFFF, null);
                hl.setText(Math.round(hi.getDouble(i)) + "° " + Math.round(lo.getDouble(i)) + "°");
                col.addView(hl);
                days.addView(col, new LayoutParams(0, -2, 1f));
            }
            setVisibility(VISIBLE);
        } catch (Exception e) {
            android.util.Log.w("Harbor", "weather render: " + e);
        }
    }

    private static boolean geocode(String place, double[] ll, String[] name) throws Exception {
        String q = place.trim();
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                + URLEncoder.encode(q, "UTF-8") + (q.matches("\\d{5}") ? "&countryCode=US" : "");
        JSONObject j = new JSONObject(get(url));
        JSONArray r = j.optJSONArray("results");
        if (r == null || r.length() == 0) return false;
        JSONObject o = r.getJSONObject(0);
        ll[0] = o.getDouble("latitude");
        ll[1] = o.getDouble("longitude");
        name[0] = o.optString("name", place);
        return true;
    }

    private static boolean ipLocate(double[] ll, String[] name) {
        try {
            JSONObject j = new JSONObject(get("https://ipapi.co/json/"));
            if (j.has("latitude")) {
                ll[0] = j.getDouble("latitude"); ll[1] = j.getDouble("longitude");
                name[0] = j.optString("city", "");
                return true;
            }
        } catch (Exception ignored) { }
        try {
            JSONObject j = new JSONObject(get("https://ipwho.is/"));
            if (j.optBoolean("success", false)) {
                ll[0] = j.getDouble("latitude"); ll[1] = j.getDouble("longitude");
                name[0] = j.optString("city", "");
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "HarborLauncher/1.5");
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }
}
