package com.zhihuyitu.care.ui;

import org.junit.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocationRulesTest {
    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 24, 9, 0);

    @Test public void minuteRangeIncludesEntireLastMinute() {
        LocationRepository.Range range = new LocationRepository.Range(TIME, TIME.plusMinutes(2));
        assertEquals(TIME, range.since);
        assertEquals(TIME.plusMinutes(2).plusSeconds(59), range.until);
    }

    @Test public void fenceDoesNotRetriggerAtOuterEdgeAndRearmsAfterAContinuousMinuteInside() {
        LocationFence.Config fence = new LocationFence.Config(point(0), 200, 50, 60);
        LocationFence.Tracker tracker = new LocationFence.Tracker();
        assertFalse(tracker.advance(fence, 200, TIME));
        assertFalse(tracker.advance(fence, 250, TIME.plusSeconds(5)));
        assertTrue(tracker.advance(fence, 251, TIME.plusSeconds(10)));
        assertFalse(tracker.advance(fence, 240, TIME.plusSeconds(20)));
        assertFalse(tracker.advance(fence, 251, TIME.plusSeconds(30)));
        assertFalse(tracker.advance(fence, 190, TIME.plusSeconds(35)));
        assertFalse(tracker.advance(fence, 195, TIME.plusSeconds(65)));
        assertFalse(tracker.advance(fence, 180, TIME.plusSeconds(95)));
        assertFalse(tracker.triggered);
        assertTrue(tracker.advance(fence, 251, TIME.plusSeconds(96)));
    }

    @Test public void durationDoesNotAccumulateAcrossDroppedOrRepeatedFixes() {
        LocationFence.Config fence = new LocationFence.Config(point(0), 200, 50, 60);
        LocationFence.Tracker tracker = new LocationFence.Tracker();
        assertTrue(tracker.advance(fence, 270, TIME));
        assertFalse(tracker.advance(fence, 100, TIME.plusSeconds(5)));
        assertFalse(tracker.advance(fence, 100, TIME.plusSeconds(5)));
        assertFalse(tracker.advance(fence, 100, TIME.plusSeconds(125)));
        assertTrue(tracker.triggered);
        assertFalse(tracker.advance(fence, 300, TIME.plusSeconds(130)));
    }

    @Test public void staysRequireThirtyMinutesAndSplitOnSamplingGaps() {
        List<LocationRepository.Point> points = new ArrayList<>();
        for (int minute = 0; minute <= 30; minute += 5) points.add(point(minute));
        points.add(point(60));
        points.add(point(65));
        List<LocationStays.Stay> stays = LocationStays.detect(points, 50);
        assertEquals(1, stays.size());
        assertEquals(30, stays.get(0).minutes());
        assertEquals(TIME.plusMinutes(30), stays.get(0).last.time);
    }

    private LocationRepository.Point point(int minute) {
        return new LocationRepository.Point(0, 0, TIME.plusMinutes(minute));
    }
}
