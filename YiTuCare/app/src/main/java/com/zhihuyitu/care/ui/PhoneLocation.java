package com.zhihuyitu.care.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;
import androidx.core.location.LocationManagerCompat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

final class PhoneLocation implements LocationListener {
    interface Listener {
        void onPhoneLocation(LocationRepository.Point point, String message);
    }

    private final Context context;
    private final LocationManager manager;
    private final Listener listener;
    private Location latest;
    private boolean listening;

    PhoneLocation(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        manager = context.getSystemService(LocationManager.class);
    }

    boolean hasPermission() {
        return precise() || permitted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private boolean precise() {
        return permitted(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean permitted(String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    void start() {
        stop();
        latest = null;
        if (!hasPermission()) {
            listener.onPhoneLocation(null, "手机定位未授权");
            return;
        }
        if (manager == null || !LocationManagerCompat.isLocationEnabled(manager)) {
            listener.onPhoneLocation(null, "手机定位服务未开启");
            return;
        }
        listener.onPhoneLocation(null, "正在获取手机位置…");
        listening = true;
        boolean subscribed = false;
        for (String provider : manager.getProviders(true)) {
            if (!"fused".equals(provider) && !LocationManager.NETWORK_PROVIDER.equals(provider)
                    && !(precise() && LocationManager.GPS_PROVIDER.equals(provider))) continue;
            try {
                manager.requestLocationUpdates(provider, 5000, 2, this, Looper.getMainLooper());
                subscribed = true;
                Location cached = manager.getLastKnownLocation(provider);
                if (cached != null) onLocationChanged(cached);
            } catch (SecurityException | IllegalArgumentException unavailable) {
                // Some enabled providers require a different accuracy permission.
            }
        }
        if (!subscribed) {
            listening = false;
            listener.onPhoneLocation(null, "当前无法获取手机位置");
        }
    }

    void stop() {
        listening = false;
        if (manager != null) manager.removeUpdates(this);
    }

    void checkAge() {
        if (listening && latest != null && !fresh(latest)) {
            latest = null;
            listener.onPhoneLocation(null, "手机位置已过期，等待更新");
        }
    }

    @Override public void onLocationChanged(Location location) {
        if (!listening || !fresh(location)) return;
        if (latest != null && (location.getElapsedRealtimeNanos() < latest.getElapsedRealtimeNanos()
                || (Math.abs(location.getElapsedRealtimeNanos() - latest.getElapsedRealtimeNanos())
                    < TimeUnit.SECONDS.toNanos(5) && latest.hasAccuracy() && location.hasAccuracy()
                    && location.getAccuracy() > latest.getAccuracy() * 2))) return;
        latest = location;
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochMilli(location.getTime()), LocationRepository.BEIJING);
        listener.onPhoneLocation(new LocationRepository.Point(location.getLongitude(), location.getLatitude(), time),
                precise() ? "已获取手机位置" : "已获取手机大致位置");
    }

    @Override public void onProviderEnabled(String provider) {}
    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override public void onProviderDisabled(String provider) {
        if (manager != null && !LocationManagerCompat.isLocationEnabled(manager)) {
            latest = null;
            listener.onPhoneLocation(null, "手机定位服务未开启");
        }
    }

    private static boolean fresh(Location location) {
        long age = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
        return age >= 0 && age <= TimeUnit.SECONDS.toNanos(LocationRepository.STALE_AFTER.getSeconds());
    }
}
