package com.zhihuyitu.care.ui;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zhihuyitu.care.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class LocationFragment extends Fragment {
    static final String RANGE_REQUEST = "location_range_request";
    static final String HOME_REQUEST = "location_home_request";
    static final String HOME_ACTION = "action";
    static final String HOME_LIVE = "live";
    static final String HOME_TODAY = "today";
    private int pendingRangeAction;
    private static final String[] RANGE_OPTIONS = {"今日轨迹", "昨日轨迹", "近一周轨迹", "自选时间"};
    private static final String[] TRAVEL_MODES = {"驾车", "公交", "步行", "骑行"};
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable polling = new Runnable() {
        @Override public void run() {
            if (model.isPhoneTest() && trackRange != null
                    && !trackRange.first.equals(LocationRepository.now().toLocalDate())) showTodayTrack();
            handler.postDelayed(this, 5000);
        }
    };

    private LocationViewModel model;
    private final ActivityResultLauncher<String[]> permissions = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                model.refreshPhone();
            });

    private LocationRepository repository;
    private LocationFence fence;
    private LocationMap map;
    private LocationPlaceSheet places;
    private boolean providerReady;
    private int providerAttempt;
    private View mapStatus;
    private TextView source;
    private TextView status;
    private TextView addressView;
    private TextView fixTime;
    private TextView phoneStatus;
    private TextView fenceStatus;
    private TextView fenceButton;
    private View crosshair;
    private View cancelFenceSelection;
    private TextView stayHeading;
    private View stayScroll;
    private LinearLayout stayList;
    private View retryMap;

    private LocationViewModel.Fix fix;
    private LocationRepository.Point phonePoint;
    private String phoneMessage = "正在获取手机位置…";
    private LocationRepository.Range trackRange;
    private LocalDateTime lookupTime;
    private LocationViewModel.Track track;
    private LocationViewModel.Nearest nearest;
    private int historyRequest;
    private int addressCounter;
    private int liveAddressRequest;
    private String address = "";
    private int detailAddressRequest;
    private AlertDialog addressDialog;
    private String detailText;
    private boolean choosingFence;
    private int fenceRequest;
    private Dialog activeDialog;

    public LocationFragment() {
        super(R.layout.fragment_location);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        model = new ViewModelProvider(requireActivity()).get(LocationViewModel.class);
        repository = model.repository();
        fence = model.fence();
        mapStatus = view.findViewById(R.id.location_map_status);
        source = view.findViewById(R.id.location_source);
        status = view.findViewById(R.id.location_status);
        addressView = view.findViewById(R.id.location_address);
        fixTime = view.findViewById(R.id.location_fix_time);
        phoneStatus = view.findViewById(R.id.location_phone_status);
        fenceStatus = view.findViewById(R.id.location_fence_status);
        fenceButton = view.findViewById(R.id.location_fence);
        crosshair = view.findViewById(R.id.location_crosshair);
        cancelFenceSelection = view.findViewById(R.id.location_fence_cancel);
        cancelFenceSelection.setOnClickListener(button -> setFenceChoosing(false));
        stayHeading = view.findViewById(R.id.location_stay_heading);
        stayScroll = view.findViewById(R.id.location_stay_scroll);
        stayList = view.findViewById(R.id.location_stay_list);
        retryMap = view.findViewById(R.id.location_enable_map);

        map = new LocationMap(view.findViewById(R.id.location_map), this::onMapMessage);
        places = new LocationPlaceSheet(this, map, handler);
        model.phoneFix().observe(getViewLifecycleOwner(), fix -> onPhoneFix(fix.point, fix.message));
        model.fenceChanges().observe(getViewLifecycleOwner(), change -> {
            renderFence();
            redrawMap();
        });
        getParentFragmentManager().setFragmentResultListener(RANGE_REQUEST, getViewLifecycleOwner(), (key, result) -> {
            pendingRangeAction = result.getBoolean("edit") ? 1 : 2;
            applyRangeAction();
        });
        retryMap.setOnClickListener(button -> startProvider());
        View recenter = view.findViewById(R.id.location_recenter);
        View myLocation = view.findViewById(R.id.location_phone);
        TextView history = view.findViewById(R.id.location_history);
        if (model.isPhoneTest()) {
            recenter.setContentDescription(getString(R.string.location_recenter_test));
            recenter.setOnClickListener(button -> showPhone());
            myLocation.setVisibility(View.GONE);
            history.setText(R.string.location_history_today);
            history.setOnClickListener(button -> showTodayTrack());
            model.trackWriteOk().observe(getViewLifecycleOwner(), ok ->
                    setOptionalText(phoneStatus, Boolean.TRUE.equals(ok) ? "" : "本机轨迹保存失败"));
        } else {
            recenter.setOnClickListener(button -> returnToLive("elderly"));
            myLocation.setOnClickListener(button -> showPhone());
            history.setOnClickListener(button -> chooseRange(this::requestTrack));
            model.latest().observe(getViewLifecycleOwner(), this::onFix);
        }
        view.findViewById(R.id.location_detail).setOnClickListener(button -> showDetails());
        view.findViewById(R.id.location_places).setOnClickListener(button -> places.open(phonePoint));
        fenceButton.setOnClickListener(button -> editFence());
        model.track().observe(getViewLifecycleOwner(), this::onTrack);
        model.nearest().observe(getViewLifecycleOwner(), this::onNearest);
        drawLive("");
        renderFence();
        startProvider();
        restoreHistory(state);
        getParentFragmentManager().setFragmentResultListener(HOME_REQUEST, getViewLifecycleOwner(), (key, result) -> {
            String action = result.getString(HOME_ACTION);
            if (HOME_TODAY.equals(action)) showTodayTrack();
            else if (HOME_LIVE.equals(action)) returnToLive(fix != null && fix.point != null ? "elderly" : "");
        });
    }

    private void restoreHistory(Bundle state) {
        if (state == null) return;
        String since = state.getString("since");
        String until = state.getString("until");
        String point = state.getString("point");
        if (model.isPhoneTest()) {
            if (since != null) showTodayTrack();
            return;
        }
        if (since != null && until != null) {
            LocalDateTime first = LocalDateTime.parse(since);
            LocalDateTime last = LocalDateTime.parse(until);
            requestTrack(state.getBoolean("minutes")
                    ? new LocationRepository.Range(first, last)
                    : new LocationRepository.Range(first.toLocalDate(), last.toLocalDate()));
        } else if (point != null) requestNearest(LocalDateTime.parse(point));
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        if (trackRange != null) {
            state.putString("since", trackRange.since.toString());
            state.putString("until", trackRange.until.toString());
            state.putBoolean("minutes", trackRange.minutes);
        }
        if (lookupTime != null) state.putString("point", lookupTime.toString());
    }

    @Override public void onStart() {
        super.onStart();
        handler.post(polling);
    }

    @Override public void onResume() {
        super.onResume();
        SharedPreferences prefs = requireContext().getSharedPreferences("phone_location", Context.MODE_PRIVATE);
        if (!model.hasPhonePermission() && !prefs.getBoolean("requested", false)) {
            prefs.edit().putBoolean("requested", true).apply();
            requestPhonePermission();
        }
    }


    @Override public void onStop() {
        handler.removeCallbacks(polling);
        super.onStop();
    }

    @Override public void onDestroyView() {
        if (activeDialog != null) activeDialog.dismiss();
        places.dismiss();
        handler.removeCallbacksAndMessages(null);
        map.destroy();
        map = null;
        places = null;
        phonePoint = null;
        phoneMessage = "正在获取手机位置…";
        fix = null;
        trackRange = null;
        lookupTime = null;
        track = null;
        nearest = null;
        providerReady = false;
        choosingFence = false;
        address = "";
        liveAddressRequest = 0;
        activeDialog = null;
        addressDialog = null;
        super.onDestroyView();
    }

    boolean isMapReady() { return providerReady; }

    private void requestPhonePermission() {
        permissions.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION});
    }

    private void showPhone() {
        if (!model.hasPhonePermission()) requestPhonePermission();
        else if (phonePoint == null) {
            model.refreshPhone();
            message(phoneMessage);
        } else returnToLive(model.isPhoneTest() ? "elderly" : "phone");
    }

    private void startProvider() {
        mapStatus.setVisibility(View.VISIBLE);
        retryMap.setVisibility(View.GONE);
        if (!map.isConfigured()) {
            source.setText("高德地图尚未配置");
            return;
        }
        source.setText("正在加载高德地图…");
        int attempt = ++providerAttempt;
        map.enableProvider();
        handler.postDelayed(() -> {
            if (attempt == providerAttempt && !providerReady) providerUnavailable();
        }, 18000);
    }

    private void providerUnavailable() {
        providerReady = false;
        if (choosingFence) setFenceChoosing(false);
        mapStatus.setVisibility(View.VISIBLE);
        retryMap.setVisibility(map.isConfigured() ? View.VISIBLE : View.GONE);
        source.setText("高德地图加载失败，请检查网络与配置");
    }

    private boolean isLive() { return trackRange == null && lookupTime == null; }

    private void onPhoneFix(LocationRepository.Point point, String message) {
        phonePoint = point;
        phoneMessage = message;
        places.updateLocation(point);
        if (!model.isPhoneTest()) {
            setOptionalText(phoneStatus, message);
            if (isLive()) drawLive("");
            return;
        }
        if (point == null) {
            fix = null;
            address = "";
            liveAddressRequest = 0;
            renderFence();
            if (isLive()) drawLive("");
            return;
        }
        boolean recording = model.recordPhoneFix(point);
        onFix(new LocationViewModel.Fix(point, false, false));
        if (recording && trackRange != null) {
            if (!trackRange.first.equals(LocationRepository.now().toLocalDate())) showTodayTrack();
            else historyRequest = model.loadTrack(trackRange);
        }
    }

    private void onFix(LocationViewModel.Fix update) {
        LocationRepository.Point last = fix == null ? null : fix.point;
        fix = update;

        if (update.point != null && (last == null || last.longitude != update.point.longitude
                || last.latitude != update.point.latitude)) resolveLiveAddress();
        renderFence();
        if (isLive()) drawLive("");
    }

    private void drawLive(String recenter) {
        LocationRepository.Point point = fix == null ? null : fix.point;
        if (model.isPhoneTest()) {
            status.setText(point == null ? phoneMessage : "手机实测位置");
        } else if (!repository.isConnected()) status.setText("老人设备定位待接入");
        else if (fix == null || fix.loading) status.setText("正在读取老人位置");
        else if (fix.failed) status.setText(point == null ? "老人定位读取失败" : "刷新失败 · 最后有效位置");
        else if (point == null) status.setText("暂无老人定位");
        else if (fix.isStale()) status.setText("老人定位已过期 · 最后有效位置");
        else status.setText("老人当前位置");
        setOptionalText(addressView, point == null ? "" : liveAddressLabel());
        setOptionalText(fixTime, point == null ? "" : format(point.time) + "（北京时间）");
        map.showLive(point, model.isPhoneTest() ? null : phonePoint, fence, recenter,
                model.isPhoneTest() ? "手机实测位置" : "老人位置");
    }

    private void returnToLive(String recenter) {
        setFenceChoosing(false);
        trackRange = null;
        lookupTime = null;
        track = null;
        nearest = null;
        clearStays();
        drawLive(recenter);
        if ("elderly".equals(recenter) && (fix == null || fix.point == null)) message("暂无老人有效定位");
    }

    private void redrawMap() {
        if (isLive()) drawLive("");
        else if (trackRange != null) {
            map.showTrack(track == null || track.points == null ? Collections.emptyList() : track.points,
                    track == null ? null : track.stays, fence);
        } else map.showNearest(nearest == null ? null : nearest.point, fence);
    }

    private void resolveLiveAddress() {
        address = "";
        liveAddressRequest = 0;
        if (!providerReady || fix == null || fix.point == null) return;
        int request = ++addressCounter;
        liveAddressRequest = request;
        map.reverse(request, LocationRepository.mapCoordinate(fix.point));
        handler.postDelayed(() -> {
            if (liveAddressRequest != request) return;
            liveAddressRequest = 0;
            if (isLive()) setOptionalText(addressView, liveAddressLabel());
        }, 10000);
    }

    private String liveAddressLabel() {
        if (liveAddressRequest != 0) return "正在查询地名…";
        if (!address.isEmpty()) return address;
        return providerReady ? "暂无可用地名" : "高德地图加载后可查询地名";
    }

    private void showDetails() {
        if (fix == null || fix.point == null) {
            message(model.isPhoneTest() ? "暂无手机定位" : "暂无老人位置");
            return;
        }
        LocationRepository.Point point = fix.point;
        String base = "定位时间 " + format(point.time) + "（北京时间）\n" + coordinates(point);
        if (fix.isStale() || fix.failed) base += "\n\n路线将前往最后有效位置。";
        showPoint("位置详情", point, base, address, true);
    }

    private void showPoint(String title, LocationRepository.Point point, String details, String name, boolean route) {
        String initial = name.isEmpty() ? "暂无可用地名" : name;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title).setNegativeButton("关闭", null).setMessage(initial + "\n\n" + details);
        if (route) builder.setPositiveButton("去这里", (popup, which) ->
                showTravelModes(point, name.isEmpty() ? (model.isPhoneTest() ? "手机实测位置" : "老人位置") : name));
        AlertDialog dialog = builder.create();
        showDialog(dialog);
        if (!name.isEmpty() || !providerReady) return;
        addressDialog = dialog;
        detailText = details;
        detailAddressRequest = ++addressCounter;
        map.reverse(detailAddressRequest, LocationRepository.mapCoordinate(point));
    }

    private void showTravelModes(LocationRepository.Point point, String destination) {
        showDialog(new MaterialAlertDialogBuilder(requireContext())
                .setTitle("选择出行方式")
                .setItems(TRAVEL_MODES, (dialog, which) -> openAmap(point, destination, which))
                .setNegativeButton("取消", null).create());
    }

    private void openAmap(LocationRepository.Point point, String destination, int mode) {
        Uri route = Uri.parse("amapuri://route/plan/").buildUpon()
                .appendQueryParameter("sourceApplication", getString(R.string.app_name))
                .appendQueryParameter("dlat", Double.toString(point.latitude))
                .appendQueryParameter("dlon", Double.toString(point.longitude))
                .appendQueryParameter("dname", destination)
                .appendQueryParameter("dev", "1")
                .appendQueryParameter("t", Integer.toString(mode))
                .appendQueryParameter("m", "0").build();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, route).setPackage("com.autonavi.minimap"));
        } catch (ActivityNotFoundException error) {
            showDialog(new MaterialAlertDialogBuilder(requireContext())
                    .setTitle("未找到可打开路线的高德地图")
                    .setMessage("安装高德地图后，可从位置详情打开路线。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("打开下载页", (dialog, which) -> {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://mobile.amap.com/cn/download.html")));
                        } catch (ActivityNotFoundException unavailable) {
                            message("请在手机应用商店搜索高德地图");
                        }
                    }).create());
        }
    }

    private void chooseRange(Consumer<LocationRepository.Range> selection) {
        showDialog(new MaterialAlertDialogBuilder(requireContext())
                .setTitle("选择历史轨迹")
                .setItems(RANGE_OPTIONS, (dialog, which) -> {
                    LocalDate today = LocationRepository.now().toLocalDate();
                    if (which == 0) selection.accept(new LocationRepository.Range(today, today));
                    else if (which == 1) selection.accept(new LocationRepository.Range(today.minusDays(1), today.minusDays(1)));
                    else if (which == 2) selection.accept(new LocationRepository.Range(today.minusDays(6), today));
                    else chooseCustomHistory(selection);
                })
                .setNegativeButton("取消", null).create());
    }

    private void chooseCustomHistory(Consumer<LocationRepository.Range> selection) {
        showDialog(new MaterialAlertDialogBuilder(requireContext()).setTitle("自选时间")
                .setItems(new String[]{"查询一段轨迹", "查询某一时刻的位置"}, (dialog, which) -> {
                    if (which == 0) pickInterval(selection);
                    else pickMoment("查询时间", null, LocationRepository.now().toLocalDate(), this::requestNearest);
                }).setNegativeButton("取消", null).create());
    }

    private void pickInterval(Consumer<LocationRepository.Range> selection) {
        LocalDate today = LocationRepository.now().toLocalDate();
        pickMoment("起始时间", null, today, start -> {
            LocalDate last = start.toLocalDate().plusDays(30);
            pickMoment("结束时间", start.toLocalDate(), last.isAfter(today) ? today : last, end -> {
                if (end.isBefore(start)) message("结束时间应为起始时间或之后");
                else selection.accept(new LocationRepository.Range(start, end));
            });
        });
    }

    private void pickMoment(String title, LocalDate earliest, LocalDate latest, Consumer<LocalDateTime> selection) {
        LocalDate initial = earliest == null ? latest : earliest;
        DatePickerDialog picker = new DatePickerDialog(requireContext(), (widget, year, month, day) -> {
            LocalDate date = LocalDate.of(year, month + 1, day);
            LocalDateTime now = LocationRepository.now();
            TimePickerDialog time = new TimePickerDialog(requireContext(), (clock, hour, minute) -> {
                LocalDateTime chosen = date.atTime(hour, minute);
                if (chosen.isAfter(LocationRepository.now())) message("请选择当前时间或之前的时间");
                else selection.accept(chosen);
            }, now.getHour(), now.getMinute(), true);
            time.setTitle(title + " · 时分（北京时间）");
            showDialog(time);
        }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        ZoneId zone = ZoneId.systemDefault();
        if (earliest != null) picker.getDatePicker().setMinDate(earliest.atStartOfDay(zone).toInstant().toEpochMilli());
        picker.getDatePicker().setMaxDate(latest.atStartOfDay(zone).toInstant().toEpochMilli());
        picker.setTitle(title + " · 日期（北京时间）");
        showDialog(picker);
    }

    private void showTodayTrack() {
        LocalDate today = LocationRepository.now().toLocalDate();
        requestTrack(new LocationRepository.Range(today, today));
    }

    private void requestTrack(LocationRepository.Range range) {
        setFenceChoosing(false);
        trackRange = range;
        lookupTime = null;
        track = null;
        nearest = null;
        clearStays();
        setOptionalText(addressView, range.toString());
        setOptionalText(fixTime, "");
        boolean available = model.isPhoneTest() || repository.isConnected();
        status.setText(available ? "正在读取轨迹…" : "设备历史轨迹待接入");
        redrawMap();
        if (available) historyRequest = model.loadTrack(range);
    }

    private void onTrack(LocationViewModel.Track result) {
        if (trackRange == null || result.request != historyRequest) return;
        track = result;
        String title = model.isPhoneTest() ? "今日轨迹" : "历史轨迹";
        if (result.points == null) status.setText(title + "读取失败");
        else if (result.points.isEmpty()) {
            status.setText(model.isPhoneTest() ? "今日暂无轨迹" : "所选时段暂无轨迹记录");
        } else {
            status.setText(title + " · " + result.points.size() + " 个定位点");
            setOptionalText(fixTime, result.points.size() == 1 ? "移动后会显示路线" : "绿起点 · 红终点");
            if (!model.isPhoneTest()) renderStays(result.stays);
        }
        redrawMap();
    }

    private void clearStays() {
        stayHeading.setVisibility(View.GONE);
        stayScroll.setVisibility(View.GONE);
        stayList.removeAllViews();
    }

    private void renderStays(List<LocationStays.Stay> stays) {
        clearStays();
        stayHeading.setVisibility(View.VISIBLE);
        if (stays == null) {
            stayHeading.setText("停留日志 · 原始定位记录待接入");
            return;
        }
        stayHeading.setText(stays.isEmpty() ? "所选时段暂无停留记录" : "停留日志 · " + stays.size() + " 条");
        if (stays.isEmpty()) return;
        stayScroll.setVisibility(View.VISIBLE);
        for (LocationStays.Stay stay : stays) {
            MaterialButton item = new MaterialButton(requireContext());
            item.setText(stay.minutes() + " 分钟 · " + stay.first.time.format(MINUTE) + "\n" + coordinates(stay.first));
            item.setOnClickListener(button -> showStay(stay));
            stayList.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void showStay(LocationStays.Stay stay) {
        map.focus(stay.first);
        showPoint("停留详情", stay.first, "开始 " + format(stay.first.time) + "\n结束 " + format(stay.last.time)
                + "\n停留 " + stay.minutes() + " 分钟\n" + coordinates(stay.first), "", false);
    }

    private void requestNearest(LocalDateTime time) {
        setFenceChoosing(false);
        trackRange = null;
        lookupTime = time;
        track = null;
        nearest = null;
        clearStays();
        setOptionalText(addressView, "查询时间 " + time.format(MINUTE));
        setOptionalText(fixTime, "北京时间");
        status.setText(repository.isConnected() ? "正在查询最近定位…" : "设备定位记录待接入");
        redrawMap();
        if (repository.isConnected()) historyRequest = model.loadNearest(time);
    }

    private void onNearest(LocationViewModel.Nearest result) {
        if (lookupTime == null || result.request != historyRequest) return;
        nearest = result;
        if (result.failed) status.setText("最近定位查询失败");
        else if (result.point == null) status.setText("所选时间前后 5 分钟无定位信息");
        else {
            status.setText("已找到最近定位");
            setOptionalText(fixTime, "实际定位 " + format(result.point.time));
        }
        redrawMap();
    }

    private void showNearestDetails() {
        if (nearest == null || nearest.point == null) return;
        long difference = Duration.between(nearest.time, nearest.point.time).abs().getSeconds();
        showPoint("历史位置详情", nearest.point, "查询时间 " + nearest.time.format(MINUTE)
                + "\n实际定位 " + format(nearest.point.time) + "\n相差 " + difference + " 秒（北京时间）\n"
                + coordinates(nearest.point), "", false);
    }

    private void applyRangeAction() {
        if (pendingRangeAction == 0 || !providerReady) return;
        int action = pendingRangeAction;
        pendingRangeAction = 0;
        returnToLive("");
        if (action == 1) editFence();
        else map.focusRange();
    }

    private void editFence() {
        if (!providerReady) { message("地图加载后可设置范围提醒"); return; }
        if (choosingFence) {
            map.pickCenter(++fenceRequest);
            return;
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext()).setTitle("范围提醒")
                .setMessage("在地图上划定安全范围。超出外侧缓冲区时提醒一次，回到安全范围并停留一会儿后恢复提醒。")
                .setPositiveButton("选范围中心", (dialog, which) -> setFenceChoosing(true))
                .setNegativeButton("关闭", null);
        if (fence.config() != null) builder.setNeutralButton("关闭提醒", (dialog, which) -> {
            model.configureFence(null);
            renderFence();
            redrawMap();
        });
        showDialog(builder.create());
    }

    private void setFenceChoosing(boolean choosing) {
        choosingFence = choosing;
        crosshair.setVisibility(choosing ? View.VISIBLE : View.GONE);
        cancelFenceSelection.setVisibility(choosing ? View.VISIBLE : View.GONE);
        fenceButton.setText(choosing ? "确定中心" : getString(R.string.location_fence));
        if (choosing) {
            map.pauseFollowing();
            setOptionalText(fenceStatus, "拖动地图，把准星移到安全范围中心");
        } else renderFence();
    }

    private void renderFence() {
        if (choosingFence) return;
        LocationFence.Config config = fence.config();
        if (config == null) setOptionalText(fenceStatus, "");
        else if (fence.triggered()) setOptionalText(fenceStatus, "已提醒 · 等待回到安全范围");
        else if (fix == null || fix.point == null || fix.isStale() || fix.failed) {
            setOptionalText(fenceStatus, "范围提醒已设置 · 等待定位");
        } else setOptionalText(fenceStatus, "安全范围 " + config.radius + " 米 · 缓冲 " + config.buffer + " 米");
    }

    private void showFenceForm(LocationRepository.Point center) {
        setFenceChoosing(false);
        View body = getLayoutInflater().inflate(R.layout.dialog_location_fence, null);
        ((TextView) body.findViewById(R.id.fence_coordinates)).setText(coordinates(center));
        EditText radius = body.findViewById(R.id.fence_radius);
        EditText buffer = body.findViewById(R.id.fence_buffer);
        EditText returnTime = body.findViewById(R.id.fence_return);
        LocationFence.Config existing = fence.config();
        radius.setText(Integer.toString(existing == null ? LocationFence.DEFAULT_RADIUS : existing.radius));
        buffer.setText(Integer.toString(existing == null ? LocationFence.DEFAULT_BUFFER : existing.buffer));
        returnTime.setText(Integer.toString(existing == null ? LocationFence.DEFAULT_RETURN_SECONDS : existing.returnSeconds));
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext()).setTitle("设置提醒范围").setView(body)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        showDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                model.configureFence(new LocationFence.Config(center, number(radius), number(buffer), number(returnTime)));
                renderFence();
                redrawMap();
                dialog.dismiss();
            } catch (IllegalArgumentException error) {
                message("请输入半径 10–50000 米、缓冲 1–10000 米和恢复时间 60–3600 秒");
            }
        });
    }

    private static int number(EditText input) {
        return Integer.parseInt(input.getText().toString().trim());
    }

    private void onMapMessage(String type, JSONObject data) {
        if (!isAdded() || map == null) return;
        switch (type) {
            case "ready":
                providerReady = true;
                mapStatus.setVisibility(View.GONE);
                resolveLiveAddress();
                redrawMap();
                applyRangeAction();
                break;
            case "unavailable":
                providerUnavailable();
                break;
            case "marker":
                if (isLive() && !choosingFence) showDetails();
                break;
            case "nearest":
                if (!choosingFence) showNearestDetails();
                break;
            case "stay":
                int index = data.optInt("index", -1);
                if (!choosingFence && track != null && track.stays != null && index >= 0 && index < track.stays.size()) {
                    showStay(track.stays.get(index));
                }
                break;
            case "center":
                if (choosingFence && data.optInt("request", -1) == fenceRequest) {
                    try {
                        showFenceForm(new LocationRepository.Point(data.getDouble("longitude"), data.getDouble("latitude"), null));
                    } catch (JSONException | IllegalArgumentException error) {
                        setFenceChoosing(false);
                        message("无法读取地图中心");
                    }
                }
                break;
            case "address":
                onAddress(data);
                break;
            case "places":
                places.onPlaces(data);
                break;
            default:
                break;
        }
    }

    private void onAddress(JSONObject data) {
        int request = data.optInt("request", -1);
        String resolved = data.optString("address");
        if (request == liveAddressRequest && providerReady) {
            liveAddressRequest = 0;
            address = resolved;
            if (isLive()) setOptionalText(addressView, liveAddressLabel());
        }
        if (request == detailAddressRequest && addressDialog != null && addressDialog.isShowing()) {
            addressDialog.setMessage((resolved.isEmpty() ? "暂无可用地名" : resolved) + "\n\n" + detailText);
        }
    }

    void showDialog(Dialog dialog) {
        if (activeDialog != null) activeDialog.dismiss();
        activeDialog = dialog;
        dialog.setOnDismissListener(event -> {
            if (activeDialog == dialog) activeDialog = null;
            if (addressDialog == dialog) addressDialog = null;
        });
        dialog.show();
        dimBackground(dialog);
    }

    void dimBackground(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.48f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window.getWindowManager().isCrossWindowBlurEnabled()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.setBlurBehindRadius(Math.round(14 * getResources().getDisplayMetrics().density));
            window.setAttributes(attributes);
        }
    }

    private static void setOptionalText(TextView view, String text) {
        view.setText(text);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static String format(LocalDateTime time) { return time.format(LocationRepository.CLOCK); }

    private static String coordinates(LocationRepository.Point point) {
        return String.format(Locale.ROOT, "纬度 %.6f · 经度 %.6f", point.latitude, point.longitude);
    }

    private void message(String text) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }
}
