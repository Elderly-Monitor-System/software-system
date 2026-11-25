package com.zhihuyitu.care.ui;

import com.zhihuyitu.care.BuildConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class LocationStays {
    private static final int DEFAULT_RADIUS_METERS = 50;
    private static final Duration MINIMUM_STAY = Duration.ofMinutes(30);

    static final class Stay {
        final LocationRepository.Point first;
        final LocationRepository.Point last;

        Stay(LocationRepository.Point first, LocationRepository.Point last) {
            this.first = first;
            this.last = last;
        }

        long minutes() {
            return Duration.between(first.time, last.time).toMinutes();
        }
    }

    static int configuredRadius() {
        if (BuildConfig.LOCATION_STAY_RADIUS_METERS.isEmpty()) return DEFAULT_RADIUS_METERS;
        try {
            int meters = Integer.parseInt(BuildConfig.LOCATION_STAY_RADIUS_METERS);
            return meters >= 10 && meters <= 500 ? meters : DEFAULT_RADIUS_METERS;
        } catch (NumberFormatException error) {
            return DEFAULT_RADIUS_METERS;
        }
    }

    static List<Stay> detect(List<LocationRepository.Point> ordered, int radiusMeters) {
        List<Stay> stays = new ArrayList<>();
        LocationRepository.Point first = null;
        LocationRepository.Point last = null;
        for (LocationRepository.Point point : ordered) {
            if (first == null) {
                first = point;
            } else if (LocationRepository.isGap(last, point)
                    || LocationRepository.distanceMeters(first, point) > radiusMeters) {
                addIfLongEnough(stays, first, last);
                first = point;
            }
            last = point;
        }
        if (first != null) addIfLongEnough(stays, first, last);
        return stays;
    }

    private static void addIfLongEnough(List<Stay> stays, LocationRepository.Point first,
                                       LocationRepository.Point last) {
        if (Duration.between(first.time, last.time).compareTo(MINIMUM_STAY) >= 0) {
            stays.add(new Stay(first, last));
        }
    }

    private LocationStays() {}
}
