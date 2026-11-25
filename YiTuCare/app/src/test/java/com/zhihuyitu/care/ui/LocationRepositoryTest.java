package com.zhihuyitu.care.ui;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocationRepositoryTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 24, 9, 0);

    @Test public void singleDayAndThirtyOneDaysAreValid() {
        LocalDate first = START.toLocalDate();
        assertEquals("2026-11-24", new LocationRepository.Range(first, first).toString());
        assertEquals(first.plusDays(30), new LocationRepository.Range(first, first.plusDays(30)).last);
    }

    @Test(expected = IllegalArgumentException.class)
    public void endBeforeStartIsRejected() {
        LocalDate first = START.toLocalDate();
        new LocationRepository.Range(first, first.minusDays(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void moreThanThirtyOneDaysIsRejected() {
        LocalDate first = START.toLocalDate();
        new LocationRepository.Range(first, first.plusDays(31));
    }

    @Test public void placeResultsUseThePhoneDistanceAndKeepProviderOrderWithoutIt() {
        LocationPlaceSheet.Result near = new LocationPlaceSheet.Result("a", "Near", "", point(0.001, 0));
        LocationPlaceSheet.Result far = new LocationPlaceSheet.Result("b", "Far", "", point(0.020, 0));
        List<LocationPlaceSheet.Result> results = Arrays.asList(far, near);
        LocationPlaceSheet.sortByDistance(results, null);
        assertEquals(far, results.get(0));
        assertEquals("", near.distanceLabel());
        LocationPlaceSheet.sortByDistance(results, point(0, 0));
        assertEquals(near, results.get(0));
        assertTrue(near.distanceLabel().endsWith("米"));
        assertTrue(far.distanceLabel().endsWith("公里"));
    }

    @Test public void trackSplitsOnlyAfterTheSamplingGap() {
        assertFalse(LocationRepository.isGap(point(0, 0), point(0, 5)));
        assertTrue(LocationRepository.isGap(point(0, 7), point(0, 13)));
    }

    private LocationRepository.Point point(double latitude, int minutes) {
        return new LocationRepository.Point(0, latitude, START.plusMinutes(minutes));
    }
}
