package com.zhihuyitu.care.ui;

import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.zhihuyitu.care.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class LocationMap {
    private static final String PREFIX = "YITU_MAP:";
    private static final String LOCAL_ORIGIN = "https://location-preview.invalid";
    private final WebView view;
    private boolean loaded;
    private String pendingData;
    private boolean startPending;

    interface Listener {
        void onMapMessage(String type, JSONObject data);
    }

    LocationMap(WebView view, Listener listener) {
        this.view = view;
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView web, WebResourceRequest request) {
                return request.isForMainFrame();
            }


            @Override public void onPageFinished(WebView web, String url) {
                if (!originsMatch(url, origin())) return;
                loaded = true;
                if (pendingData != null) evaluate(pendingData);
                if (startPending) enableProvider();
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                String value = message.message();
                if (!value.startsWith(PREFIX) || !originsMatch(message.sourceId(), origin())) return true;
                try {
                    JSONObject data = new JSONObject(value.substring(PREFIX.length()));
                    listener.onMapMessage(data.getString("type"), data);
                } catch (JSONException ignored) {
                    // A malformed message cannot change the page state.
                }
                return true;
            }
        });
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                view.getContext().getAssets().open("location_map.html"), StandardCharsets.UTF_8))) {
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) html.append(line).append('\n');
            view.loadDataWithBaseURL(origin() + "/", html.toString(), "text/html", "UTF-8", null);
        } catch (IOException error) {
            listener.onMapMessage("unavailable", new JSONObject());
        }
    }

    boolean isConfigured() {
        if (BuildConfig.AMAP_JS_KEY.isEmpty()
                || (!BuildConfig.AMAP_ORIGIN.isEmpty() && !isHttps(BuildConfig.AMAP_ORIGIN))) {
            return false;
        }
        if (!BuildConfig.AMAP_SERVICE_HOST.isEmpty()) {
            return isHttps(BuildConfig.AMAP_SERVICE_HOST) && !LOCAL_ORIGIN.equals(origin());
        }
        return BuildConfig.DEBUG && !BuildConfig.AMAP_SECURITY_CODE.isEmpty();
    }

    void enableProvider() {
        if (!isConfigured()) return;
        if (!loaded) {
            startPending = true;
            return;
        }
        startPending = false;
        JSONObject config = new JSONObject();
        try {
            config.put("key", BuildConfig.AMAP_JS_KEY);
            if (isHttps(BuildConfig.AMAP_SERVICE_HOST)) config.put("serviceHost", BuildConfig.AMAP_SERVICE_HOST);
            else config.put("securityCode", BuildConfig.AMAP_SECURITY_CODE);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
        evaluate("startMap(" + config + ")");
    }

    void showLive(LocationRepository.Point elderly, LocationRepository.Point phone,
                  LocationFence fence, String recenter, String title) {
        JSONObject data = frame("live", fence);
        put(data, "elderly", mapped(elderly));
        put(data, "phone", mapped(phone));
        put(data, "elderlyTitle", title);
        put(data, "reset", recenter);
        show(data);
    }

    void showTrack(List<LocationRepository.Point> points, List<LocationStays.Stay> stays, LocationFence fence) {
        JSONArray blocks = new JSONArray();
        JSONArray block = null;
        LocationRepository.Point previous = null;
        for (LocationRepository.Point point : points) {
            if (block == null || LocationRepository.isGap(previous, point)) {
                block = new JSONArray();
                blocks.put(block);
            }
            block.put(mapped(point));
            previous = point;
        }
        JSONArray stops = new JSONArray();
        if (stays != null) {
            for (LocationStays.Stay stay : stays) stops.put(mapped(stay.first));
        }
        JSONObject data = frame("track", fence);
        put(data, "blocks", blocks);
        put(data, "stays", stops);
        show(data);
    }

    void showNearest(LocationRepository.Point point, LocationFence fence) {
        JSONObject data = frame("point", fence);
        put(data, "point", mapped(point));
        show(data);
    }

    void focus(LocationRepository.Point point) {
        if (loaded) evaluate("focus(" + mapped(point) + ")");
    }

    void focusRange() {
        if (loaded) evaluate("focusRange()");
    }

    void pauseFollowing() {
        if (loaded) evaluate("pauseFollowing()");
    }

    void pickCenter(int request) {
        if (loaded) evaluate("pickCenter(" + request + ")");
    }

    private JSONObject frame(String mode, LocationFence fence) {
        JSONObject data = new JSONObject();
        put(data, "mode", mode);
        LocationFence.Config config = fence.config();
        if (config != null) {
            put(data, "fence", new JSONArray().put(coordinate(config.center)).put(config.radius)
                    .put(config.buffer).put(fence.triggered()));
        }
        return data;
    }

    private void show(JSONObject data) {
        pendingData = "setData(" + data + ")";
        if (loaded) evaluate(pendingData);
    }

    private static JSONArray mapped(LocationRepository.Point point) {
        return point == null ? null : coordinate(LocationRepository.mapCoordinate(point));
    }

    private static void put(JSONObject data, String key, Object value) {
        try {
            data.put(key, value);
        } catch (JSONException error) {
            throw new IllegalArgumentException(error);
        }
    }


    void reverse(int request, LocationRepository.Point position) {
        if (loaded) evaluate("reverse(" + request + "," + coordinate(position) + ")");
    }

    void search(int request, int page, int pageSize, String keyword, LocationRepository.Point center) {
        String position = center == null ? "null" : coordinate(center).toString();
        if (loaded) evaluate("search(" + request + "," + page + "," + pageSize + ","
                + JSONObject.quote(keyword) + "," + position + ")");
    }

    void destroy() {
        if (view.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) view.getParent()).removeView(view);
        }
        view.stopLoading();
        view.destroy();
    }

    private void evaluate(String method) {
        view.evaluateJavascript("window.locationMap && window.locationMap." + method, null);
    }

    private static JSONArray coordinate(LocationRepository.Point point) {
        JSONArray value = new JSONArray();
        try {
            value.put(point.longitude);
            value.put(point.latitude);
        } catch (JSONException error) {
            throw new IllegalArgumentException(error);
        }
        return value;
    }

    private static String origin() {
        String value = BuildConfig.AMAP_ORIGIN.trim();
        if (!isHttps(value)) return LOCAL_ORIGIN;
        return "https://" + URI.create(value).getRawAuthority();
    }

    private static boolean isHttps(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static boolean originsMatch(String first, String second) {
        if (first == null || !isHttps(first)) return false;
        URI a = URI.create(first);
        URI b = URI.create(second);
        return a.getHost().equalsIgnoreCase(b.getHost()) && a.getPort() == b.getPort();
    }
}
