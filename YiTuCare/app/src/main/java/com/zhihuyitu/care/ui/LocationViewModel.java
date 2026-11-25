package com.zhihuyitu.care.ui;

import android.app.Application;
import android.database.sqlite.SQLiteException;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.zhihuyitu.care.BuildConfig;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocationViewModel extends AndroidViewModel {
    private final LocationRepository repository = new LocationRepository();
    private final LocationFence fence;
    private final PhoneLocation phoneLocation;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<PhoneFix> phoneFix = new MutableLiveData<>();
    private final MutableLiveData<Integer> fenceChanges = new MutableLiveData<>(0);
    private boolean monitoring;
    private int fenceVersion;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            phoneLocation.checkAge();
            main.postDelayed(this, 5000);
        }
    };
    private final DailyPhoneTrack phoneTrack;
    private final ExecutorService liveRequests = Executors.newSingleThreadExecutor();
    private final ExecutorService historyRequests = Executors.newSingleThreadExecutor();
    private final MutableLiveData<Fix> latest = new MutableLiveData<>(new Fix(null, false, true));
    private final MutableLiveData<Track> track = new MutableLiveData<>();
    private final MutableLiveData<Nearest> nearest = new MutableLiveData<>();
    private final MutableLiveData<Boolean> trackWriteOk = new MutableLiveData<>(true);
    private LocationRepository.Point lastPoint;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private int requestId;

    public LocationViewModel(Application application) {
        super(application);
        fence = new LocationFence(application);
        phoneTrack = BuildConfig.LOCATION_PHONE_TEST ? new DailyPhoneTrack(application) : null;
        phoneLocation = new PhoneLocation(application, (point, message) -> {
            if (isPhoneTest() && point != null) observeFence(point);
            phoneFix.setValue(new PhoneFix(point, message));
        });
        if (phoneTrack != null) {
            historyRequests.execute(() -> {
                try {
                    phoneTrack.retainOnly(LocationRepository.now().toLocalDate());
                } catch (SQLiteException error) {
                    trackWriteOk.postValue(false);
                }
            });
        }
    }

    static final class PhoneFix {
        final LocationRepository.Point point;
        final String message;

        PhoneFix(LocationRepository.Point point, String message) {
            this.point = point;
            this.message = message;
        }
    }

    public void startMonitoring() {
        monitoring = true;
        phoneLocation.start();
        main.post(poll);
    }

    public void stopMonitoring() {
        monitoring = false;
        main.removeCallbacks(poll);
        phoneLocation.stop();
    }

    void refreshPhone() {
        if (monitoring) phoneLocation.start();
    }

    boolean hasPhonePermission() { return phoneLocation.hasPermission(); }
    LiveData<PhoneFix> phoneFix() { return phoneFix; }
    LiveData<Integer> fenceChanges() { return fenceChanges; }

    void configureFence(LocationFence.Config config) {
        fence.configure(config);
        fenceChanges.setValue(++fenceVersion);
    }

    private void observeFence(LocationRepository.Point point) {
        boolean previous = fence.triggered();
        if (fence.observe(point)) {
            Toast.makeText(getApplication(), isPhoneTest() ? "手机已超出安全范围，已记录提醒"
                    : "老人已超出安全范围，已记录提醒", Toast.LENGTH_LONG).show();
        }
        if (previous != fence.triggered()) fenceChanges.setValue(++fenceVersion);
    }

    static final class Fix {
        final LocationRepository.Point point;
        final boolean failed;
        final boolean loading;

        Fix(LocationRepository.Point point, boolean failed, boolean loading) {
            this.point = point;
            this.failed = failed;
            this.loading = loading;
        }

        boolean isStale() {
            return point != null && !LocationRepository.isCurrent(point.time, LocationRepository.now());
        }
    }

    static final class Track {
        final int request;
        final List<LocationRepository.Point> points;
        final List<LocationStays.Stay> stays;

        Track(int request, List<LocationRepository.Point> points, List<LocationStays.Stay> stays) {
            this.request = request;
            this.points = points;
            this.stays = stays;
        }
    }

    static final class Nearest {
        final int request;
        final LocalDateTime time;
        final LocationRepository.Point point;
        final boolean failed;

        Nearest(int request, LocalDateTime time, LocationRepository.Point point, boolean failed) {
            this.request = request;
            this.time = time;
            this.point = point;
            this.failed = failed;
        }
    }

    LocationRepository repository() { return repository; }
    LocationFence fence() { return fence; }
    LiveData<Fix> latest() { return latest; }
    LiveData<Track> track() { return track; }
    LiveData<Nearest> nearest() { return nearest; }
    LiveData<Boolean> trackWriteOk() { return trackWriteOk; }
    boolean isPhoneTest() { return phoneTrack != null; }

    boolean recordPhoneFix(LocationRepository.Point point) {
        if (phoneTrack == null || point == null || !LocationRepository.isCurrent(point.time, LocationRepository.now())) return false;
        LocalDate day = LocationRepository.now().toLocalDate();
        if (!point.time.toLocalDate().equals(day)) return false;
        historyRequests.execute(() -> {
            try {
                phoneTrack.record(point, LocationRepository.now().toLocalDate());
                if (!Boolean.TRUE.equals(trackWriteOk.getValue())) trackWriteOk.postValue(true);
            } catch (SQLiteException error) {
                trackWriteOk.postValue(false);
            }
        });
        return true;
    }

    private void refresh() {
        if (isPhoneTest() || !repository.isConnected() || !refreshing.compareAndSet(false, true)) return;
        liveRequests.execute(() -> {
            try {
                LocationRepository.Point point = repository.latest();
                if (point != null && (lastPoint == null || !point.time.isBefore(lastPoint.time))) lastPoint = point;
                LocationRepository.Point current = lastPoint;
                main.post(() -> {
                    if (monitoring && point != null) observeFence(point);
                    latest.setValue(new Fix(current, false, false));
                });
            } catch (Exception error) {
                LocationRepository.Point current = lastPoint;
                main.post(() -> latest.setValue(new Fix(current, true, false)));
            } finally {
                refreshing.set(false);
            }
        });
    }

    int loadTrack(LocationRepository.Range range) {
        int request = ++requestId;
        historyRequests.execute(() -> {
            try {
                if (phoneTrack != null) {
                    List<LocationRepository.Point> points = phoneTrack.points(LocationRepository.now().toLocalDate());
                    track.postValue(new Track(request, points, Collections.emptyList()));
                    return;
                }
                List<LocationRepository.Point> points = repository.history(range);
                List<LocationRepository.Point> raw = repository.rawHistory(range);
                List<LocationStays.Stay> stays = raw == null ? null
                        : LocationStays.detect(raw, LocationStays.configuredRadius());
                track.postValue(new Track(request, points, stays));
            } catch (Exception error) {
                track.postValue(new Track(request, null, null));
            }
        });
        return request;
    }

    int loadNearest(LocalDateTime time) {
        int request = ++requestId;
        historyRequests.execute(() -> {
            try {
                nearest.postValue(new Nearest(request, time, repository.nearest(time), false));
            } catch (Exception error) {
                nearest.postValue(new Nearest(request, time, null, true));
            }
        });
        return request;
    }

    @Override protected void onCleared() {
        stopMonitoring();
        main.removeCallbacksAndMessages(null);
        liveRequests.shutdownNow();
        if (phoneTrack != null) {
            historyRequests.execute(phoneTrack::close);
            historyRequests.shutdown();
        } else {
            historyRequests.shutdownNow();
        }
    }
}
