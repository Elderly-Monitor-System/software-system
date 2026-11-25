package com.zhihuyitu.care.ui;

import android.location.Address;
import android.location.Geocoder;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.zhihuyitu.care.R;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class HomeFragment extends Fragment {
    private static final double ADDRESS_REFRESH_METERS = 75;
    private static final DateTimeFormatter DISPLAY_TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);

    private LocationRepository.Point shownPoint;
    private LocationRepository.Point addressPoint;
    private String address;
    private ExecutorService legacyGeocoder;

    public HomeFragment() {
        super(R.layout.fragment_home);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        LocationViewModel location = new ViewModelProvider(requireActivity()).get(LocationViewModel.class);
        AlertRepository alerts = new AlertRepository(requireContext());
        LifecycleOwner owner = getViewLifecycleOwner();

        // TODO FR-002/003: Use the authorized device name, heartbeat and last report time.
        view.findViewById(R.id.home_device_card).setOnClickListener(card ->
                openTab(R.id.profileFragment, ProfileFragment.DEVICE_REQUEST, Bundle.EMPTY));
        view.findViewById(R.id.home_alert_card).setOnClickListener(card ->
                openTab(R.id.alertsFragment, AlertsFragment.HOME_REQUEST, Bundle.EMPTY));
        view.findViewById(R.id.home_location_card).setOnClickListener(card ->
                openLocation(LocationFragment.HOME_LIVE));
        view.findViewById(R.id.home_today_card).setOnClickListener(card ->
                openLocation(LocationFragment.HOME_TODAY));
        view.findViewById(R.id.home_range_card).setOnClickListener(card -> {
            Bundle request = new Bundle();
            request.putBoolean("edit", location.fence().config() == null);
            openTab(R.id.locationFragment, LocationFragment.RANGE_REQUEST, request);
        });

        location.fenceChanges().observe(owner, change -> renderAlerts(view, alerts, location.fence()));

        TextView locationSummary = view.findViewById(R.id.home_location_summary);
        TextView locationMeta = view.findViewById(R.id.home_location_meta);
        if (location.isPhoneTest()) {
            location.phoneFix().observe(owner, fix -> renderLocation(locationSummary, locationMeta,
                    fix.point, getString(R.string.home_location_phone_test), fix.message, false));
        } else {
            location.latest().observe(owner, fix -> {
                int status = R.string.home_location_empty;
                if (!location.repository().isConnected()) status = R.string.home_location_pending;
                else if (fix.loading) status = R.string.home_location_loading;
                else if (fix.failed) status = R.string.home_location_failed;
                renderLocation(locationSummary, locationMeta, fix.point,
                        getString(R.string.home_location_device), getString(status), fix.failed || fix.isStale());
            });
        }
    }

    private void openLocation(String action) {
        Bundle request = new Bundle();
        request.putString(LocationFragment.HOME_ACTION, action);
        openTab(R.id.locationFragment, LocationFragment.HOME_REQUEST, request);
    }

    private void openTab(@IdRes int destination, String requestKey, Bundle request) {
        getParentFragmentManager().setFragmentResult(requestKey, request);
        BottomNavigationView navigation = requireActivity().findViewById(R.id.bottom_nav);
        navigation.setSelectedItemId(destination);
    }

    private void renderAlerts(View view, AlertRepository alerts, LocationFence fence) {
        TextView summary = view.findViewById(R.id.home_alert_summary);
        TextView meta = view.findViewById(R.id.home_alert_meta);
        List<AlertRepository.Event> events = alerts.events();
        if (events.isEmpty()) {
            summary.setText(R.string.home_alerts_empty);
            meta.setText(R.string.home_alerts_pending);
        } else {
            AlertRepository.Event event = events.get(0);
            summary.setText(event.kind.title);
            int label = event.kind == AlertRepository.Kind.RANGE && fence.triggered()
                    ? R.string.home_alerts_active : R.string.home_alerts_latest;
            meta.setText(getString(label, eventTime(event.occurredAt)));
        }

        TextView range = view.findViewById(R.id.home_range_summary);
        LocationFence.Config config = fence.config();
        if (config == null) range.setText(R.string.home_range_unset);
        else if (fence.triggered()) range.setText(R.string.home_range_active);
        else range.setText(getString(R.string.home_range_radius, config.radius));
    }

    private void renderLocation(TextView summary, TextView meta, LocationRepository.Point point,
                                String source, String status, boolean stale) {
        shownPoint = point;
        if (point == null) {
            summary.setText(status);
            meta.setText(source);
            return;
        }
        if (!nearAddress(point)) {
            addressPoint = stale ? null : point;
            address = null;
            if (!stale) resolveAddress(summary, point);
        }
        summary.setText(address == null ? getString(R.string.home_location_coordinates,
                point.latitude, point.longitude) : address);
        int label = stale ? R.string.home_location_stale : R.string.home_location_marked;
        meta.setText(getString(label, source, point.time.format(DISPLAY_TIME)));
    }

    private boolean nearAddress(LocationRepository.Point point) {
        return addressPoint != null
                && LocationRepository.distanceMeters(addressPoint, point) < ADDRESS_REFRESH_METERS;
    }

    private void resolveAddress(TextView summary, LocationRepository.Point point) {
        if (!Geocoder.isPresent()) return;
        LifecycleOwner owner = getViewLifecycleOwner();
        Geocoder geocoder = new Geocoder(requireContext().getApplicationContext(), Locale.SIMPLIFIED_CHINESE);
        Executor main = ContextCompat.getMainExecutor(requireContext());
        Consumer<List<Address>> showAddress = results -> {
            if (results == null || results.isEmpty()) return;
            String line = results.get(0).getAddressLine(0);
            if (line == null || line.trim().isEmpty()) return;
            main.execute(() -> {
                if (owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED
                        || addressPoint != point) return;
                address = line;
                if (shownPoint != null && nearAddress(shownPoint)) summary.setText(address);
            });
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(point.latitude, point.longitude, 1, showAddress::accept);
        } else {
            if (legacyGeocoder == null) legacyGeocoder = Executors.newSingleThreadExecutor();
            legacyGeocoder.execute(() -> {
                try {
                    showAddress.accept(geocoder.getFromLocation(point.latitude, point.longitude, 1));
                } catch (IOException unavailable) {
                    // The location remains visible as coordinates.
                }
            });
        }
    }

    private static String eventTime(String time) {
        try {
            return LocalDateTime.parse(time, LocationRepository.CLOCK).format(DISPLAY_TIME);
        } catch (DateTimeParseException unfamiliarFormat) {
            return time;
        }
    }

    @Override public void onDestroyView() {
        if (legacyGeocoder != null) {
            legacyGeocoder.shutdownNow();
            legacyGeocoder = null;
        }
        shownPoint = null;
        addressPoint = null;
        address = null;
        super.onDestroyView();
    }
}
