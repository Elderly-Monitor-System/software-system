package com.zhihuyitu.care.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.zhihuyitu.care.BuildConfig;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LocationFence {
    static final int DEFAULT_RADIUS = 200;
    static final int DEFAULT_BUFFER = 50;
    static final int DEFAULT_RETURN_SECONDS = 60;

    static final class Config {
        final LocationRepository.Point center;
        final int radius;
        final int buffer;
        final int returnSeconds;

        Config(LocationRepository.Point center, int radius, int buffer, int returnSeconds) {
            if (radius < 10 || radius > 50000 || buffer < 1 || buffer > 10000
                    || returnSeconds < 60 || returnSeconds > 3600) {
                throw new IllegalArgumentException("Invalid fence limits");
            }
            this.center = center;
            this.radius = radius;
            this.buffer = buffer;
            this.returnSeconds = returnSeconds;
        }
    }

    static final class Tracker {
        LocalDateTime latest;
        LocalDateTime insideSince;
        boolean triggered;

        boolean advance(Config config, double distance, LocalDateTime time) {
            if (latest != null && !time.isAfter(latest)) return false;
            if (latest != null && Duration.between(latest, time).compareTo(LocationRepository.STALE_AFTER) > 0) {
                insideSince = null;
            }
            latest = time;

            if (distance <= config.radius) {
                if (triggered) {
                    if (insideSince == null) insideSince = time;
                    if (Duration.between(insideSince, time).getSeconds() >= config.returnSeconds) {
                        triggered = false;
                        insideSince = null;
                    }
                }
                return false;
            }

            insideSince = null;
            if (distance <= config.radius + config.buffer || triggered) return false;
            triggered = true;
            return true;
        }
    }

    private final SharedPreferences preferences;
    private Tracker tracker = new Tracker();
    private Config config;

    LocationFence(Context context) {
        preferences = preferences(context);
        if (!preferences.contains("longitude")) return;
        config = new Config(new LocationRepository.Point(
                Double.longBitsToDouble(preferences.getLong("longitude", 0)),
                Double.longBitsToDouble(preferences.getLong("latitude", 0)), null),
                preferences.getInt("radius", DEFAULT_RADIUS), preferences.getInt("buffer", DEFAULT_BUFFER),
                preferences.getInt("returnSeconds", DEFAULT_RETURN_SECONDS));
        tracker.triggered = preferences.getBoolean("triggered", false);
        tracker.latest = readTime("latest");
        tracker.insideSince = readTime("insideSince");
    }

    private static SharedPreferences preferences(Context context) {
        String name = BuildConfig.LOCATION_PHONE_TEST
                ? "phone_test_location_fence" : "location_fence_" + BuildConfig.LOCATION_DEVICE_ID;
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    static List<String> alerts(Context context) {
        List<String> alarms = new ArrayList<>(preferences(context).getStringSet("alerts", Collections.emptySet()));
        alarms.sort(Collections.reverseOrder());
        return alarms;
    }

    Config config() { return config; }
    boolean triggered() { return tracker.triggered; }

    void configure(Config newConfig) {
        // TODO FR-020: Save and load the device's fence through the authenticated Flask API.
        config = newConfig;
        tracker = new Tracker();
        SharedPreferences.Editor edit = preferences.edit()
                .remove("triggered").remove("latest").remove("insideSince");
        if (newConfig == null) {
            edit.remove("longitude").remove("latitude").remove("radius").remove("buffer").remove("returnSeconds");
        } else {
            edit.putLong("longitude", Double.doubleToLongBits(newConfig.center.longitude))
                    .putLong("latitude", Double.doubleToLongBits(newConfig.center.latitude))
                    .putInt("radius", newConfig.radius).putInt("buffer", newConfig.buffer)
                    .putInt("returnSeconds", newConfig.returnSeconds);
        }
        edit.apply();
    }

    boolean observe(LocationRepository.Point point) {
        if (config == null || point == null || !LocationRepository.isCurrent(point.time, LocationRepository.now())
                || (tracker.latest != null && !point.time.isAfter(tracker.latest))) return false;
        double distance = LocationRepository.distanceMeters(config.center, LocationRepository.mapCoordinate(point));
        boolean alarm = tracker.advance(config, distance, point.time);
        SharedPreferences.Editor edit = preferences.edit().putBoolean("triggered", tracker.triggered)
                .putString("latest", tracker.latest.format(LocationRepository.CLOCK));
        if (tracker.insideSince == null) edit.remove("insideSince");
        else edit.putString("insideSince", tracker.insideSince.format(LocationRepository.CLOCK));
        if (alarm) {
            // TODO FR-020: Submit the crossing to Flask with an idempotent event key and sync acknowledgment.
            List<String> recent = new ArrayList<>(alertsFromPreferences());
            String timestamp = point.time.format(LocationRepository.CLOCK);
            if (!recent.contains(timestamp)) recent.add(timestamp);
            recent.sort(Collections.reverseOrder());
            Set<String> retained = new HashSet<>(recent.subList(0, Math.min(50, recent.size())));
            edit.putStringSet("alerts", retained);
        }
        edit.apply();
        return alarm;
    }

    private Set<String> alertsFromPreferences() {
        return preferences.getStringSet("alerts", Collections.emptySet());
    }

    private LocalDateTime readTime(String key) {
        String value = preferences.getString(key, null);
        return value == null ? null : LocalDateTime.parse(value, LocationRepository.CLOCK);
    }
}
