package com.zhihuyitu.care.ui;

import com.zhihuyitu.care.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class LocationRepository {
    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT);
    static final Duration STALE_AFTER = Duration.ofMinutes(1);
    static final Duration TRACK_GAP = Duration.ofMinutes(5);

    private static final String BASE = BuildConfig.LOCATION_BASE_URL.trim();
    private static final String DEVICE = BuildConfig.LOCATION_DEVICE_ID.trim();
    static final class Point {
        final double longitude;
        final double latitude;
        final LocalDateTime time;

        Point(double longitude, double latitude, LocalDateTime time) {
            if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                    || Math.abs(longitude) > 180 || Math.abs(latitude) > 90) {
                throw new IllegalArgumentException("Invalid coordinate");
            }
            this.longitude = longitude;
            this.latitude = latitude;
            this.time = time;
        }
    }

    static final class Range {
        final LocalDate first;
        final LocalDate last;
        final LocalDateTime since;
        final LocalDateTime until;
        final boolean minutes;

        Range(LocalDate first, LocalDate last) {
            this(first.atStartOfDay(), last.atTime(23, 59, 59), false);
        }

        Range(LocalDateTime since, LocalDateTime until) {
            this(since.withSecond(0).withNano(0), until.withSecond(59).withNano(0), true);
        }

        private Range(LocalDateTime since, LocalDateTime until, boolean minutes) {
            first = since.toLocalDate();
            last = until.toLocalDate();
            if (!until.isAfter(since) || last.toEpochDay() - first.toEpochDay() >= 31) {
                throw new IllegalArgumentException("Invalid date range");
            }
            this.since = since;
            this.until = until;
            this.minutes = minutes;
        }

        @Override public String toString() {
            if (!minutes) return first.equals(last) ? first.toString() : first + " 至 " + last;
            DateTimeFormatter format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);
            return since.format(format) + " 至 " + until.format(format);
        }
    }


    boolean isConnected() {
        return !BASE.isEmpty() && !DEVICE.isEmpty();
    }

    Point latest() throws IOException, JSONException {
        // TODO FR-004: Bind the authorized device and attach the account session when Flask supports it.
        if (!isConnected()) return null;
        JSONObject body = request("/api/location", "");
        return body.getBoolean("found") ? readPoint(body) : null;
    }

    List<Point> history(Range range) throws IOException, JSONException {
        // TODO FR-004: Use the authorized history endpoint when authentication is available.
        JSONObject body = request("/api/locations/range", "since=" + encode(range.since.format(CLOCK))
                + "&until=" + encode(range.until.format(CLOCK)));
        JSONArray rows = body.getJSONArray("locations");
        List<Point> points = new ArrayList<>();
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.getJSONObject(index);
            if (!DEVICE.equals(row.getString("device_id"))) throw new IOException("Unexpected device");
            Point point = readPoint(row);
            if (point.time.isBefore(range.since) || point.time.isAfter(range.until)) {
                throw new IOException("Point outside the requested range");
            }
            points.add(point);
        }
        points.sort(Comparator.comparing(point -> point.time));
        return points;
    }

    Point nearest(LocalDateTime query) throws IOException, JSONException {
        // TODO FR-004: When the server supports this tie rule, do both comparisons in one authorized query.
        Point result = nearestFromServer(query);
        if (result != null && result.time.isAfter(query)) {
            // Moving the existing window by one second makes an equally close earlier point win.
            Point earlier = nearestFromServer(query.minusSeconds(1));
            if (earlier != null && earlier.time.isBefore(query)
                    && Duration.between(earlier.time, query).equals(Duration.between(query, result.time))) {
                return earlier;
            }
        }
        return result;
    }

    private Point nearestFromServer(LocalDateTime query) throws IOException, JSONException {
        String clock = query.format(CLOCK);
        JSONObject body = request("/api/locations/nearest", "date_str=" + encode(clock.substring(0, 10))
                + "&time_str=" + encode(clock.substring(11)));
        if (!body.getBoolean("found")) return null;
        Point point = readPoint(body);
        if (Duration.between(query, point.time).abs().compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IOException("Point outside the requested time window");
        }
        return point;
    }

    List<Point> rawHistory(Range range) {
        // TODO FR-004: Fetch this range from the future authenticated raw GPS history endpoint.
        // Existing Flask tracks are sampled by distance, so missing observations cannot confirm a stay.
        return null;
    }


    static LocalDateTime now() {
        return LocalDateTime.now(BEIJING);
    }

    static boolean isCurrent(LocalDateTime time, LocalDateTime now) {
        Duration age = Duration.between(time, now);
        return age.compareTo(STALE_AFTER) <= 0 && age.compareTo(Duration.ofSeconds(-10)) >= 0;
    }

    static boolean isGap(Point first, Point second) {
        return Duration.between(first.time, second.time).compareTo(TRACK_GAP) > 0;
    }

    private JSONObject request(String path, String query) throws IOException, JSONException {
        if (!DEVICE.matches("[A-Za-z0-9_.-]{1,32}")) throw new IOException("Invalid device");
        URI base = URI.create(BASE);
        boolean loopback = "10.0.2.2".equals(base.getHost()) || "127.0.0.1".equals(base.getHost());
        boolean allowed = "https".equalsIgnoreCase(base.getScheme())
                || (BuildConfig.DEBUG && loopback && "http".equalsIgnoreCase(base.getScheme()));
        if (!allowed || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IOException("Invalid server URL");
        }
        String root = BASE.endsWith("/") ? BASE.substring(0, BASE.length() - 1) : BASE;
        URI uri = URI.create(root + path + "?device_id=" + encode(DEVICE)
                + (query.isEmpty() ? "" : "&" + query));
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setUseCaches(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Location request failed");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 4 * 1024 * 1024) throw new IOException("Track too large");
                    bytes.write(buffer, 0, count);
                }
                JSONObject body = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                if (!DEVICE.equals(body.optString("device_id"))) throw new IOException("Unexpected device");
                return body;
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static Point readPoint(JSONObject body) throws JSONException {
        return new Point(body.getDouble("longitude"), body.getDouble("latitude"),
                LocalDateTime.parse(body.getString("gps_time"), CLOCK));
    }

    static double distanceMeters(Point first, Point second) {
        double lat1 = Math.toRadians(first.latitude);
        double lat2 = Math.toRadians(second.latitude);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(second.longitude - first.longitude);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2), 2);
        return 12_742_000 * Math.asin(Math.sqrt(Math.min(1, a)));
    }

    static Point mapCoordinate(Point point) {
        if (point.longitude < 72.004 || point.longitude > 137.8347
                || point.latitude < 0.8293 || point.latitude > 55.8271) return point;
        // Same WGS84 to GCJ-02 transform as the Flask map, used only for display and POI matching.
        double x = point.longitude - 105;
        double y = point.latitude - 35;
        double shared = (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        double dLat = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x)) + shared
                + (20 * Math.sin(y * Math.PI) + 40 * Math.sin(y * Math.PI / 3)) * 2 / 3
                + (160 * Math.sin(y * Math.PI / 12) + 320 * Math.sin(y * Math.PI / 30)) * 2 / 3;
        double dLon = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x)) + shared
                + (20 * Math.sin(x * Math.PI) + 40 * Math.sin(x * Math.PI / 3)) * 2 / 3
                + (150 * Math.sin(x * Math.PI / 12) + 300 * Math.sin(x * Math.PI / 30)) * 2 / 3;
        double radians = Math.toRadians(point.latitude);
        double eccentricity = 0.00669342162296594323;
        double magic = 1 - eccentricity * Math.pow(Math.sin(radians), 2);
        double root = Math.sqrt(magic);
        dLat = Math.toDegrees(dLat / (6378245 * (1 - eccentricity) / (magic * root)));
        dLon = Math.toDegrees(dLon / (6378245 / root * Math.cos(radians)));
        return new Point(point.longitude + dLon, point.latitude + dLat, point.time);
    }
}
