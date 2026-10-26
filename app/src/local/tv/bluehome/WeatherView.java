package local.tv.bluehome;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.json.JSONException;
import org.json.JSONObject;

/** A transparent, non-focusable overlay. Weather comes from Open-Meteo's model. */
public final class WeatherView extends View {
    private static final String TAG = "BlueHomeWeather";
    private static final long REFRESH_MS = 30L * 60L * 1000L;
    private static final long MAX_AGE_MS = 3L * 60L * 60L * 1000L;
    private static final long FUTURE_TOLERANCE_MS = 10L * 60L * 1000L;
    private static final long CLOCK_RECHECK_MS = 10000L;
    private static final int TIMEOUT_MS = 5000;
    private static final int MAX_JSON_BYTES = 32768;
    // SHA-256 of the official, self-signed ISRG Root X1 certificate's DER encoding.
    private static final String ISRG_X1_SHA256 =
            "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6";
    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    // A zero-core pool releases its sole worker when idle; it never runs parallel requests.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 10L,
            TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), new ThreadFactory() {
        @Override public Thread newThread(Runnable action) {
            Thread thread = new Thread(action, "BlueHomeWeather");
            thread.setDaemon(true);
            return thread;
        }
    });
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path iconPath = new Path();
    private volatile boolean active;
    private volatile boolean initialized;
    private volatile Config configuration;
    private volatile WeatherData weather;
    private volatile String lastError = "";
    private volatile String lastTlsTrust = "not-connected";
    // This is a conservative lower bound, never a claim of verified current time.
    // Only packaged model timestamps or validated cached/network data can advance it.
    private volatile long clockEvidenceEpochMs = Build.TIME;
    private boolean resumed;
    private boolean attached;
    private boolean busy;
    private boolean clockReceiverRegistered;
    private long clockGeneration;
    private long lastAttemptElapsed = -REFRESH_MS;
    private String temperatureText = "";
    private int shownCode = -1;
    private int shownDay = 1;
    private String description = "天气暂不可用";
    private float viewportScale = 1f;
    private float originX;
    private float originY;

    private final BroadcastReceiver clockChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!active || intent == null) return;
            if (Intent.ACTION_TIME_CHANGED.equals(intent.getAction())) {
                clockGeneration++;
                lastAttemptElapsed = -REFRESH_MS;
            }
            // A corrected clock can make both the cache and TLS valid immediately.
            // Time-zone changes only need a new local timestamp description.
            scheduleRefresh();
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() { scheduleRefresh(); }
    };
    private final Runnable expire = new Runnable() {
        @Override public void run() {
            if (!active) return;
            publishDisplay();
            worker.execute(new Runnable() {
                @Override public void run() { writeDebug(); }
            });
            scheduleRefresh();
        }
    };

    public WeatherView(Context context) {
        super(context);
        appContext = context.getApplicationContext();
        setFocusable(false);
        setFocusableInTouchMode(false);
        setClickable(false);
        setWillNotDraw(false);
        setContentDescription(description);
        linePaint.setColor(Color.argb(239, 255, 255, 255));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1.5f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        textPaint.setColor(Color.argb(245, 255, 255, 255));
        textPaint.setTextSize(24f);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        textPaint.setSubpixelText(true);
    }

    public void onResume() {
        resumed = true;
        updateActiveState();
    }

    public void onPause() {
        resumed = false;
        updateActiveState();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        updateActiveState();
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        updateActiveState();
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (handler != null) updateActiveState();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (handler != null) updateActiveState();
    }

    private void updateActiveState() {
        boolean visible = resumed && attached && getWindowVisibility() == VISIBLE && isShown();
        if (active == visible) return;
        active = visible;
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(expire);
        if (active) {
            registerClockChanges();
            publishDisplay();
            scheduleRefresh();
        } else if (clockReceiverRegistered) {
            appContext.unregisterReceiver(clockChanges);
            clockReceiverRegistered = false;
        }
        // An in-flight request can finish within its timeouts, but cannot trigger another
        // request while paused. No disconnect or other blocking I/O runs on the UI thread.
    }

    private void registerClockChanges() {
        if (clockReceiverRegistered) return;
        IntentFilter changes = new IntentFilter();
        changes.addAction(Intent.ACTION_TIME_CHANGED);
        changes.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        appContext.registerReceiver(clockChanges, changes, null, handler);
        clockReceiverRegistered = true;
    }

    private void scheduleRefresh() {
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(expire);
        if (!active) return;
        publishDisplay();
        WeatherData current = weather;
        long now = System.currentTimeMillis();
        if (initialized && !isClockPlausible(now)) {
            lastError = "clock-not-ready";
            publishDisplay();
            handler.postDelayed(refresh, CLOCK_RECHECK_MS);
            return;
        }
        if ("clock-not-ready".equals(lastError)) lastError = "";
        if (isFresh(current, now)) {
            handler.postDelayed(expire, Math.max(1L, current.timeEpochMs + MAX_AGE_MS - now + 1L));
        }
        if (busy) return;
        long remaining = REFRESH_MS - (SystemClock.elapsedRealtime() - lastAttemptElapsed);
        if (initialized && remaining > 0) {
            handler.postDelayed(refresh, remaining);
            return;
        }
        busy = true;
        lastAttemptElapsed = SystemClock.elapsedRealtime();
        final long requestClockGeneration = clockGeneration;
        worker.execute(new Runnable() {
            @Override public void run() {
                boolean attempted = false;
                try {
                    if (!initialized) {
                        loadConfigurationAndCache();
                        handler.post(new Runnable() {
                            @Override public void run() {
                                publishDisplay();
                                if (active) scheduleRefresh();
                            }
                        });
                    }
                    if (active && configuration != null && isClockPlausible(System.currentTimeMillis())) {
                        attempted = true;
                        fetchWeather(configuration);
                    } else if (configuration != null && !isClockPlausible(System.currentTimeMillis())) {
                        lastError = "clock-not-ready";
                    }
                    writeDebug();
                } finally {
                    final boolean attemptMade = attempted;
                    handler.post(new Runnable() {
                        @Override public void run() {
                            busy = false;
                            // Leaving home before its initial worker starts must not defer
                            // the first real network attempt for another thirty minutes.
                            if (!attemptMade && configuration != null) lastAttemptElapsed = -REFRESH_MS;
                            if (requestClockGeneration != clockGeneration
                                    && (lastError.length() != 0 || !isFresh(weather, System.currentTimeMillis()))) {
                                lastAttemptElapsed = -REFRESH_MS;
                            }
                            if (active) scheduleRefresh();
                        }
                    });
                }
            }
        });
    }

    private void loadConfigurationAndCache() {
        try {
            InputStream input = appContext.getAssets().open("weather-location.json");
            JSONObject root;
            try { root = new JSONObject(readSmallUtf8(input)); }
            finally { input.close(); }
            double latitude = root.getDouble("latitude");
            double longitude = root.getDouble("longitude");
            if (!finite(latitude) || !finite(longitude) || latitude < -90 || latitude > 90
                    || longitude < -180 || longitude > 180) throw new JSONException("coordinates");
            String city = root.optString("city", "").trim();
            if (city.length() > 80) city = city.substring(0, 80);
            Config config = new Config(latitude, longitude, city);
            configuration = config;
            SharedPreferences prefs = appContext.getSharedPreferences("weather-v1", Context.MODE_PRIVATE);
            WeatherData cached = null;
            if (config.key.equals(prefs.getString("location", ""))) {
                try {
                    String source = prefs.getString("source", "");
                    if ("open-meteo".equals(source) || "asset-seed".equals(source)) {
                        cached = checkedData(Double.parseDouble(prefs.getString("temperature", "NaN")),
                                prefs.getInt("code", -1), prefs.getInt("is_day", -1),
                                prefs.getLong("timeEpochMs", 0L), prefs.getLong("fetchedAtEpochMs", 0L), source);
                    }
                } catch (Exception ignored) { cached = null; }
            }
            WeatherData seed = null;
            JSONObject seedJson = root.optJSONObject("seed");
            if (seedJson != null) {
                try {
                    seed = checkedData(seedJson.getDouble("temperature"), seedJson.getInt("code"),
                            seedJson.getInt("is_day"), seedJson.getLong("timeEpochMs"),
                            0L, "asset-seed");
                } catch (Exception ignored) { seed = null; }
            }
            rememberClockEvidence(cached);
            rememberClockEvidence(seed);
            weather = cached;
            long now = System.currentTimeMillis();
            if (seed != null && (cached == null || seed.timeEpochMs > cached.timeEpochMs)) {
                weather = seed;
                // Keep the newest evidence even before clock sync, but never display
                // or persist an old/future seed as fresh merely because the app started.
                if (isFresh(seed, now)) saveCache(config, seed);
            }
            lastError = "";
        } catch (IOException error) {
            lastError = "config-missing";
        } catch (Exception error) {
            configuration = null;
            weather = null;
            lastError = "config-invalid";
            Log.w(TAG, "Weather configuration invalid");
        } finally {
            initialized = true;
        }
    }

    private void fetchWeather(Config config) {
        HttpsURLConnection request = null;
        WeatherTrustManager trust = null;
        try {
            if (!active) return;
            if (!isClockPlausible(System.currentTimeMillis())) {
                lastError = "clock-not-ready";
                return;
            }
            String requestUrl = "https://api.open-meteo.com/v1/forecast?latitude="
                    + Double.toString(config.latitude) + "&longitude=" + Double.toString(config.longitude)
                    + "&current=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=1&timeformat=unixtime";
            request = (HttpsURLConnection) new URL(requestUrl).openConnection();
            request.setConnectTimeout(TIMEOUT_MS);
            request.setReadTimeout(TIMEOUT_MS);
            request.setInstanceFollowRedirects(false);
            request.setUseCaches(false);
            request.setRequestProperty("Accept", "application/json");
            request.setRequestProperty("User-Agent", "BlueHome/1.0 (Android TV weather)");
            SSLContext tls = SSLContext.getInstance("TLSv1.2");
            trust = createWeatherTrustManager();
            lastTlsTrust = "system";
            tls.init(null, new TrustManager[] { trust }, null);
            // This factory affects only this weather connection. HttpsURLConnection's
            // default hostname verifier is deliberately left untouched.
            request.setSSLSocketFactory(new Tls12SocketFactory(tls.getSocketFactory()));
            if (!active) return;
            int status = request.getResponseCode();
            if (status != 200) {
                lastError = "HTTP-" + status;
                Log.w(TAG, "Weather refresh failed: " + lastError);
                return;
            }
            InputStream input = request.getInputStream();
            JSONObject response;
            try { response = new JSONObject(readSmallUtf8(input)); }
            finally { input.close(); }
            JSONObject current = response.getJSONObject("current");
            long epochSeconds = current.getLong("time");
            if (epochSeconds < 1L || epochSeconds > Long.MAX_VALUE / 1000L) throw new JSONException("time");
            WeatherData fresh = checkedData(current.getDouble("temperature_2m"),
                    current.getInt("weather_code"), current.getInt("is_day"), epochSeconds * 1000L,
                    System.currentTimeMillis(), "open-meteo");
            if (fresh == null || !isFresh(fresh, System.currentTimeMillis())) {
                lastError = "stale-response";
                Log.w(TAG, "Weather refresh failed: stale response");
                return;
            }
            if (!active) return;
            rememberClockEvidence(fresh);
            weather = fresh;
            saveCache(config, fresh);
            lastError = "";
            Log.i(TAG, "Weather refresh OK; source=Open-Meteo; dataTime=" + fresh.timeEpochMs
                    + "; trust=" + (trust.bundledUsed ? "bundled-isrg-x1" : "system"));
        } catch (Exception error) {
            lastError = errorClasses(error);
            Log.w(TAG, "Weather refresh failed: " + lastError);
        } finally {
            if (trust != null) lastTlsTrust = trust.bundledUsed ? "bundled-isrg-x1" : "system";
            if (request != null) request.disconnect();
        }
    }

    private WeatherTrustManager createWeatherTrustManager() throws GeneralSecurityException {
        X509TrustManager system = standardTrustManager(null);
        X509TrustManager bundled = null;
        try {
            InputStream input = appContext.getAssets().open("isrg-root-x1.pem");
            Collection<? extends Certificate> certificates;
            try { certificates = CertificateFactory.getInstance("X.509").generateCertificates(input); }
            finally { input.close(); }
            if (certificates.size() != 1) throw new CertificateException("Expected one root");
            Certificate certificate = certificates.iterator().next();
            if (!(certificate instanceof X509Certificate)) throw new CertificateException("Expected X.509");
            X509Certificate root = (X509Certificate) certificate;
            if (!ISRG_X1_SHA256.equals(sha256Hex(root.getEncoded()))) {
                throw new CertificateException("Unexpected bundled root");
            }
            if (root.getBasicConstraints() < 0
                    || !root.getSubjectX500Principal().equals(root.getIssuerX500Principal())) {
                throw new CertificateException("Expected self-signed CA");
            }
            root.checkValidity();
            root.verify(root.getPublicKey());
            KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
            roots.load(null, null);
            roots.setCertificateEntry("weather-isrg-root-x1", root);
            bundled = standardTrustManager(roots);
        } catch (Exception error) {
            // Missing or invalid packaged roots never weaken the platform trust checks.
            Log.w(TAG, "Bundled weather CA unavailable: " + errorClasses(error));
        }
        return new WeatherTrustManager(system, bundled);
    }

    private static X509TrustManager standardTrustManager(KeyStore roots) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(roots);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        }
        throw new GeneralSecurityException("No X509 trust manager");
    }

    private static String sha256Hex(byte[] encoded) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static String errorClasses(Throwable error) {
        StringBuilder result = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (depth > 0) result.append('>');
            result.append(current.getClass().getSimpleName());
            Throwable cause = current.getCause();
            if (cause == current) break;
            current = cause;
        }
        return result.toString();
    }

    private void saveCache(Config config, WeatherData data) {
        appContext.getSharedPreferences("weather-v1", Context.MODE_PRIVATE).edit()
                .putString("location", config.key)
                .putString("temperature", Double.toString(data.temperature))
                .putInt("code", data.code).putInt("is_day", data.isDay)
                .putLong("timeEpochMs", data.timeEpochMs)
                .putLong("fetchedAtEpochMs", data.fetchedAtEpochMs)
                .putString("source", data.source).commit();
    }

    private static WeatherData checkedData(double temperature, int code, int isDay,
            long timeEpochMs, long fetchedAtEpochMs, String source) {
        if (!finite(temperature) || temperature < -100 || temperature > 70 || code < 0 || code > 99
                || (isDay != 0 && isDay != 1) || timeEpochMs <= 0L) return null;
        return new WeatherData(temperature, code, isDay, timeEpochMs, fetchedAtEpochMs, source);
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private void rememberClockEvidence(WeatherData data) {
        if (data != null) clockEvidenceEpochMs = Math.max(clockEvidenceEpochMs, data.timeEpochMs);
    }

    private boolean isClockPlausible(long now) {
        return now > 0L && now >= Math.max(0L, clockEvidenceEpochMs - FUTURE_TOLERANCE_MS);
    }

    private boolean isFresh(WeatherData data, long now) {
        if (data == null || !isClockPlausible(now)) return false;
        long age = now - data.timeEpochMs;
        return age >= -FUTURE_TOLERANCE_MS && age < MAX_AGE_MS;
    }

    private void publishDisplay() {
        WeatherData data = weather;
        boolean fresh = isFresh(data, System.currentTimeMillis());
        // Only ASCII digits/minus go through the old firmware's font renderer.
        // The degree mark and the unavailable placeholder are drawn as geometry.
        String text = fresh ? Long.toString(Math.round(data.temperature)) : "";
        int code = fresh ? data.code : -1;
        int day = fresh ? data.isDay : 1;
        if (!text.equals(temperatureText) || shownCode != code || shownDay != day) {
            temperatureText = text;
            shownCode = code;
            shownDay = day;
            invalidate();
        }
        Config config = configuration;
        String city = config == null || config.city.length() == 0 ? "" : config.city + "，";
        String next = city + (fresh ? text + "摄氏度，" + weatherName(code) + "，Open-Meteo模型天气"
                + "，数据时间 " + formatTime(data.timeEpochMs) + "，"
                + ("asset-seed".equals(data.source) ? "初始缓存" : "联网缓存")
                : isClockPlausible(System.currentTimeMillis()) ? "天气暂不可用" : "等待电视校准时间")
                + (lastError.length() == 0 || "clock-not-ready".equals(lastError) ? "" : "，天气更新暂不可用");
        if (!next.equals(description)) {
            description = next;
            setContentDescription(next);
        }
    }

    private void writeDebug() {
        try {
            WeatherData data = weather;
            JSONObject debug = new JSONObject();
            long now = System.currentTimeMillis();
            debug.put("configured", configuration != null);
            debug.put("status", isFresh(data, now) ? "fresh" : "unknown");
            debug.put("source", data == null ? "none" : data.source);
            debug.put("provider", "Open-Meteo model");
            debug.put("writtenAtEpochMs", now);
            debug.put("clockPlausible", isClockPlausible(now));
            debug.put("clockEvidenceEpochMs", clockEvidenceEpochMs);
            debug.put("lastError", lastError);
            debug.put("tlsTrust", lastTlsTrust);
            if (data != null) {
                debug.put("temperature", data.temperature);
                debug.put("code", data.code);
                debug.put("is_day", data.isDay);
                debug.put("timeEpochMs", data.timeEpochMs);
                debug.put("fetchedAtEpochMs", data.fetchedAtEpochMs);
                debug.put("ageMs", now - data.timeEpochMs);
            }
            FileOutputStream output = appContext.openFileOutput("weather-debug.json", Context.MODE_PRIVATE);
            try { output.write(debug.toString().getBytes("UTF-8")); }
            finally { output.close(); }
        } catch (Exception error) {
            Log.w(TAG, "Weather debug write failed: " + error.getClass().getSimpleName());
        }
    }

    private static String readSmallUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > MAX_JSON_BYTES) throw new IOException("response-too-large");
            bytes.write(buffer, 0, count);
        }
        return bytes.toString("UTF-8");
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        viewportScale = Math.min(width / 1920f, height / 1080f);
        originX = (width - 1920f * viewportScale) * .5f;
        originY = (height - 1080f * viewportScale) * .5f;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save = canvas.save();
        canvas.translate(originX, originY);
        canvas.scale(viewportScale, viewportScale);
        // This reserved strip ends before the status divider at x=1590. Keep
        // the clock (x=1620) and the settings focus target entirely uncovered.
        canvas.clipRect(1467f, 54f, 1587f, 109f);
        // A second, offset stroke gives a little depth without a blur or an extra bitmap.
        if (temperatureText.length() > 0) {
            textPaint.setColor(Color.argb(45, 6, 17, 49));
            canvas.drawText(temperatureText, 1527f, 89f, textPaint);
            textPaint.setColor(Color.argb(245, 255, 255, 255));
            canvas.drawText(temperatureText, 1526f, 88f, textPaint);
            float degreeX = 1526f + textPaint.measureText(temperatureText) + 4.5f;
            linePaint.setStrokeWidth(1.5f);
            linePaint.setColor(Color.argb(39, 5, 16, 48));
            canvas.drawCircle(degreeX + 1f, 72f, 2.3f, linePaint);
            linePaint.setColor(Color.argb(239, 255, 255, 255));
            canvas.drawCircle(degreeX, 71f, 2.3f, linePaint);
        } else {
            linePaint.setColor(Color.argb(128, 255, 255, 255));
            canvas.drawLine(1528f, 80f, 1538f, 80f, linePaint);
            canvas.drawLine(1544f, 80f, 1554f, 80f, linePaint);
        }
        canvas.translate(1492.5f, 78f);
        int shadow = canvas.save();
        canvas.translate(1f, 1f);
        linePaint.setColor(Color.argb(39, 5, 16, 48));
        drawSymbol(canvas, shownCode, shownDay, true);
        canvas.restoreToCount(shadow);
        linePaint.setColor(Color.argb(239, 255, 255, 255));
        drawSymbol(canvas, shownCode, shownDay, false);
        canvas.restoreToCount(save);
    }

    private void drawSymbol(Canvas canvas, int code, int day, boolean shadow) {
        if (code == 0) {
            if (day == 1) drawSun(canvas, 0, 0, 8f); else drawMoon(canvas, 0, 0);
        } else if (code == 1 || code == 2) {
            if (day == 1) drawSun(canvas, -6f, -6f, 5.5f); else drawMoon(canvas, -5f, -5f);
            drawCloud(canvas, 2f, 4f, .78f, shadow);
        } else if (code == 3) {
            drawCloud(canvas, 0, 1f, 1f, shadow);
        } else if (code == 45 || code == 48) {
            canvas.drawLine(-13, -7, 12, -7, linePaint);
            canvas.drawLine(-16, 0, 16, 0, linePaint);
            canvas.drawLine(-11, 7, 13, 7, linePaint);
        } else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) {
            drawCloud(canvas, 0, -4f, .91f, shadow);
            canvas.drawLine(-8, 8, -11, 14, linePaint);
            canvas.drawLine(1, 8, -2, 14, linePaint);
            canvas.drawLine(10, 8, 7, 14, linePaint);
        } else if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            drawCloud(canvas, 0, -5f, .88f, shadow);
            drawSnowflake(canvas, -7f, 11f);
            drawSnowflake(canvas, 7f, 11f);
        } else if (code == 95 || code == 96 || code == 99) {
            drawCloud(canvas, 0, -5f, .88f, shadow);
            iconPath.reset();
            iconPath.moveTo(3, 3); iconPath.lineTo(-3, 10);
            iconPath.lineTo(2, 10); iconPath.lineTo(-3, 17);
            canvas.drawPath(iconPath, linePaint);
        } else {
            // A dim neutral weather placeholder, never a claimed cloudy condition.
            int color = linePaint.getColor();
            linePaint.setColor(shadow ? Color.argb(20, 5, 16, 48) : Color.argb(100, 255, 255, 255));
            drawCloudOutline(canvas);
            linePaint.setColor(color);
        }
    }

    private void drawSun(Canvas canvas, float x, float y, float radius) {
        canvas.drawCircle(x, y, radius, linePaint);
        float inner = radius + 3.3f;
        float outer = radius + 6.5f;
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
            canvas.drawLine(x + dx * inner, y + dy * inner, x + dx * outer, y + dy * outer, linePaint);
        }
    }

    private void drawMoon(Canvas canvas, float x, float y) {
        iconPath.reset();
        iconPath.moveTo(x + 5, y - 13);
        iconPath.cubicTo(x - 13, y - 15, x - 17, y + 9, x - 1, y + 13);
        iconPath.cubicTo(x + 7, y + 15, x + 13, y + 10, x + 14, y + 5);
        iconPath.cubicTo(x + 1, y + 9, x - 5, y - 4, x + 5, y - 13);
        canvas.drawPath(iconPath, linePaint);
    }

    private void drawCloud(Canvas canvas, float x, float y, float size, boolean shadow) {
        int saved = canvas.save();
        canvas.translate(x, y);
        canvas.scale(size, size);
        int color = linePaint.getColor();
        linePaint.setColor(shadow ? Color.argb(24, 5, 16, 48) : Color.argb(113, 255, 255, 255));
        int rear = canvas.save();
        canvas.translate(-4f, -4f);
        canvas.scale(.70f, .70f);
        drawCloudOutline(canvas);
        canvas.restoreToCount(rear);
        linePaint.setColor(shadow ? Color.argb(39, 5, 16, 48) : Color.argb(216, 255, 255, 255));
        canvas.translate(2f, 3f);
        canvas.scale(.89f, .89f);
        drawCloudOutline(canvas);
        linePaint.setColor(color);
        canvas.restoreToCount(saved);
    }

    private void drawCloudOutline(Canvas canvas) {
        iconPath.reset();
        iconPath.moveTo(-10, 9);
        iconPath.cubicTo(-19, 9, -19, -3, -10, -4);
        iconPath.cubicTo(-9, -16, 8, -17, 11, -5);
        iconPath.cubicTo(21, -5, 22, 9, 12, 9);
        iconPath.close();
        canvas.drawPath(iconPath, linePaint);
    }

    private void drawSnowflake(Canvas canvas, float x, float y) {
        canvas.drawLine(x, y - 3, x, y + 3, linePaint);
        canvas.drawLine(x - 2.6f, y - 1.5f, x + 2.6f, y + 1.5f, linePaint);
        canvas.drawLine(x - 2.6f, y + 1.5f, x + 2.6f, y - 1.5f, linePaint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        // A full-screen weather overlay must never consume input intended for
        // the launcher beneath it, including its new top-right settings button.
        return false;
    }

    @Override public boolean performClick() {
        super.performClick();
        Config config = configuration;
        WeatherData data = weather;
        String city = config == null || config.city.length() == 0 ? "未设置城市" : config.city;
        String details = city + "\n"
                + (isFresh(data, System.currentTimeMillis()) ? Math.round(data.temperature) + "摄氏度，"
                        + weatherName(data.code) : isClockPlausible(System.currentTimeMillis())
                                ? "天气暂不可用" : "等待电视校准时间")
                + "\n最近数据时间：" + (data == null ? "暂无" : formatTime(data.timeEpochMs))
                + "\n\n数据来源：Open-Meteo\nhttps://open-meteo.com/\n"
                + "许可：CC BY 4.0\nhttps://creativecommons.org/licenses/by/4.0/\n\n"
                + "天气来自数值模型，并非本地气象站实测。温度四舍五入显示。"
                + (data != null && "asset-seed".equals(data.source) ? "\n当前显示首次启动缓存。" : "");
        if (attached && getWindowToken() != null) {
            new AlertDialog.Builder(getContext()).setTitle("天气")
                    .setMessage(details).setPositiveButton("关闭", null).show();
        }
        return true;
    }

    private static String formatTime(long epochMs) {
        return new SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(new Date(epochMs));
    }

    private static String weatherName(int code) {
        if (code == 0) return "晴";
        if (code == 1 || code == 2) return "少云";
        if (code == 3) return "阴";
        if (code == 45 || code == 48) return "雾";
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "雨";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "雪";
        if (code == 95 || code == 96 || code == 99) return "雷雨";
        return "天气状况未知";
    }

    private static final class Config {
        final double latitude, longitude;
        final String city, key;
        Config(double latitude, double longitude, String city) {
            this.latitude = latitude; this.longitude = longitude; this.city = city;
            key = Double.toString(latitude) + "," + Double.toString(longitude);
        }
    }

    private static final class WeatherData {
        final double temperature;
        final int code, isDay;
        final long timeEpochMs, fetchedAtEpochMs;
        final String source;
        WeatherData(double temperature, int code, int isDay, long timeEpochMs,
                long fetchedAtEpochMs, String source) {
            this.temperature = temperature; this.code = code; this.isDay = isDay;
            this.timeEpochMs = timeEpochMs; this.fetchedAtEpochMs = fetchedAtEpochMs;
            this.source = source;
        }
    }

    /** Both delegates perform standard certificate-path validation, never trust-all. */
    private static final class WeatherTrustManager implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager bundled;
        volatile boolean bundledUsed;
        WeatherTrustManager(X509TrustManager system, X509TrustManager bundled) {
            this.system = system;
            this.bundled = bundled;
        }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            bundledUsed = false;
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException platformFailure) {
                if (bundled == null) throw platformFailure;
                // This delegate has exactly one authenticated CA, ISRG Root X1. It still
                // validates the entire path, certificate validity, and server key usage.
                bundled.checkServerTrusted(chain, authType);
                bundledUsed = true;
            }
        }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            system.checkClientTrusted(chain, authType);
        }
        @Override public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] platform = system.getAcceptedIssuers();
            if (bundled == null) return platform;
            X509Certificate[] additional = bundled.getAcceptedIssuers();
            X509Certificate[] all = new X509Certificate[platform.length + additional.length];
            System.arraycopy(platform, 0, all, 0, platform.length);
            System.arraycopy(additional, 0, all, platform.length, additional.length);
            return all;
        }
    }

    /** Enables only TLS 1.2 on this connection, retaining platform authentication. */
    private static final class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        Tls12SocketFactory(SSLSocketFactory delegate) { this.delegate = delegate; }
        private Socket secure(Socket socket) throws IOException {
            if (!(socket instanceof SSLSocket)) throw new SSLException("TLS socket required");
            SSLSocket tls = (SSLSocket) socket;
            boolean supported = false;
            for (String protocol : tls.getSupportedProtocols()) {
                if ("TLSv1.2".equals(protocol)) supported = true;
            }
            if (!supported) {
                tls.close();
                throw new SSLException("TLS 1.2 unavailable");
            }
            tls.setEnabledProtocols(new String[] { "TLSv1.2" });
            return tls;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException { return secure(delegate.createSocket()); }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean close) throws IOException {
            return secure(delegate.createSocket(socket, host, port, close));
        }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return secure(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            return secure(delegate.createSocket(host, port, local, localPort));
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return secure(delegate.createSocket(host, port));
        }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
            return secure(delegate.createSocket(host, port, local, localPort));
        }
    }
}
