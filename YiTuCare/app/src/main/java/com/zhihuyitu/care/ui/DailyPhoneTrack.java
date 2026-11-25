package com.zhihuyitu.care.ui;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

final class DailyPhoneTrack extends SQLiteOpenHelper {
    private static final String TABLE = "phone_track";

    DailyPhoneTrack(Context context) {
        this(context, "location_phone_today.db");
    }

    DailyPhoneTrack(Context context, String filename) {
        super(context, new File(context.getNoBackupFilesDir(), filename).getPath(), null, 1);
    }

    @Override public void onCreate(SQLiteDatabase database) {
        database.execSQL("CREATE TABLE phone_track (fix_time INTEGER PRIMARY KEY, longitude REAL NOT NULL, latitude REAL NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported phone track version: " + oldVersion);
    }

    void retainOnly(LocalDate day) {
        retainOnly(getWritableDatabase(), day);
    }

    void record(LocationRepository.Point point, LocalDate day) {
        if (!day.equals(point.time.toLocalDate())) return;
        SQLiteDatabase database = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("fix_time", point.time.atZone(LocationRepository.BEIJING).toEpochSecond());
        values.put("longitude", point.longitude);
        values.put("latitude", point.latitude);
        database.beginTransaction();
        try {
            retainOnly(database, day);
            database.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    List<LocationRepository.Point> points(LocalDate day) {
        SQLiteDatabase database = getWritableDatabase();
        retainOnly(database, day);
        List<LocationRepository.Point> points = new ArrayList<>();
        try (Cursor rows = database.query(TABLE, new String[]{"longitude", "latitude", "fix_time"},
                null, null, null, null, "fix_time ASC")) {
            while (rows.moveToNext()) {
                LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochSecond(rows.getLong(2)),
                        LocationRepository.BEIJING);
                points.add(new LocationRepository.Point(rows.getDouble(0), rows.getDouble(1), time));
            }
        }
        return points;
    }

    private void retainOnly(SQLiteDatabase database, LocalDate day) {
        long start = day.atStartOfDay(LocationRepository.BEIJING).toEpochSecond();
        long end = day.plusDays(1).atStartOfDay(LocationRepository.BEIJING).toEpochSecond();
        database.delete(TABLE, "fix_time < ? OR fix_time >= ?", new String[]{Long.toString(start), Long.toString(end)});
    }
}
