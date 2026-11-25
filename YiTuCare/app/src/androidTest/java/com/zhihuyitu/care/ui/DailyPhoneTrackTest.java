package com.zhihuyitu.care.ui;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class DailyPhoneTrackTest {
    @Test public void persistsTodayInTimeOrderAndRemovesYesterday() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String filename = "test-phone-track-" + UUID.randomUUID() + ".db";
        LocalDate day = LocalDate.of(2026, 11, 24);
        DailyPhoneTrack track = new DailyPhoneTrack(context, filename);
        try {
            track.record(new LocationRepository.Point(1, 1, day.atTime(9, 2)), day);
            track.record(new LocationRepository.Point(2, 2, day.atTime(9, 0)), day);
            track.record(new LocationRepository.Point(3, 3, day.atTime(9, 2)), day);
            track.close();

            List<LocationRepository.Point> today = track.points(day);
            assertEquals(2, today.size());
            assertEquals(day.atTime(9, 0), today.get(0).time);
            assertEquals(3, today.get(1).longitude, 0);

            LocalDate next = day.plusDays(1);
            assertTrue(track.points(next).isEmpty());
            track.record(new LocationRepository.Point(4, 4, day.atTime(23, 59)), next);
            assertTrue(track.points(next).isEmpty());
        } finally {
            track.close();
            context.deleteDatabase(track.getDatabaseName());
        }
    }
}
