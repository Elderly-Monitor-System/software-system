package com.zhihuyitu.care.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.zhihuyitu.care.R;

import java.util.List;

public class AlertsFragment extends Fragment {
    static final String HOME_REQUEST = "alerts_home_request";
    private AlertRepository repository;
    private LocationViewModel location;
    private TextView rangeSummary;
    private View showRange;
    private LinearLayout items;
    private TextView empty;
    private AlertDialog dialog;
    private int selectedLevel;

    public AlertsFragment() {
        super(R.layout.fragment_alerts);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedState) {
        super.onViewCreated(view, savedState);
        repository = new AlertRepository(requireContext());
        location = new ViewModelProvider(requireActivity()).get(LocationViewModel.class);
        rangeSummary = view.findViewById(R.id.alerts_range_summary);
        showRange = view.findViewById(R.id.alerts_show_range);
        items = view.findViewById(R.id.alerts_items);
        empty = view.findViewById(R.id.alerts_empty);
        view.findViewById(R.id.alerts_set_range).setOnClickListener(button -> openRange(true));
        showRange.setOnClickListener(button -> openRange(false));
        view.findViewById(R.id.alerts_data).setOnClickListener(button -> showCareData());
        view.findViewById(R.id.alerts_photos).setOnClickListener(button -> showPhotos(null));
        view.findViewById(R.id.alerts_rules).setOnClickListener(button -> showRules());
        MaterialButtonToggleGroup filter = view.findViewById(R.id.alerts_filter);
        selectedLevel = levelFor(filter.getCheckedButtonId());
        filter.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                selectedLevel = levelFor(id);
                renderEvents();
            }
        });
        getParentFragmentManager().setFragmentResultListener(HOME_REQUEST, getViewLifecycleOwner(),
                (key, result) -> filter.check(R.id.alerts_all));
        location.fenceChanges().observe(getViewLifecycleOwner(), change -> render());
        render();
    }

    @Override public void onDestroyView() {
        if (dialog != null) dialog.dismiss();
        super.onDestroyView();
    }

    private static int levelFor(int id) {
        if (id == R.id.alerts_level_one) return 1;
        if (id == R.id.alerts_level_two) return 2;
        if (id == R.id.alerts_level_three) return 3;
        return 0;
    }

    private void render() {
        LocationFence fence = location.fence();
        LocationFence.Config range = fence.config();
        showRange.setEnabled(range != null);
        if (range == null) rangeSummary.setText("选定居住地点和半径，超出范围时提醒。");
        else {
            String state = fence.triggered() ? "已提醒 · 等待回到安全范围" : "已设置";
            rangeSummary.setText(state + "\n半径 " + range.radius + " 米 · 缓冲 " + range.buffer + " 米");
        }
        renderEvents();
    }

    private void renderEvents() {
        items.removeAllViews();
        for (AlertRepository.Event event : repository.events()) {
            if (selectedLevel != 0 && selectedLevel != event.kind.level) continue;
            addItem(items, event.kind, event.occurredAt + " · " + event.status, () -> showDetail(event.kind, event));
        }
        empty.setText(selectedLevel == 0 ? "暂无本机报警记录 · 设备报警数据待接入" : "该级别的设备报警数据待接入");
        empty.setVisibility(items.getChildCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void openRange(boolean edit) {
        Bundle result = new Bundle();
        result.putBoolean("edit", edit);
        getParentFragmentManager().setFragmentResult(LocationFragment.RANGE_REQUEST, result);
        BottomNavigationView navigation = requireActivity().findViewById(R.id.bottom_nav);
        navigation.setSelectedItemId(R.id.locationFragment);
    }

    private void showRules() {
        AlertRepository.Kind[] types = AlertRepository.Kind.values();
        CharSequence[] titles = new CharSequence[types.length - 1];
        for (int i = 0; i < titles.length; i++) {
            titles[i] = types[i].levelText() + "\n" + types[i].title;
        }
        present(new MaterialAlertDialogBuilder(requireContext())
                .setTitle("六类报警规则")
                .setItems(titles, (popup, which) -> showDetail(types[which], null))
                .setNegativeButton("关闭", null));
    }

    private void addItem(LinearLayout parent, AlertRepository.Kind kind, String summary, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_alert, parent, false);
        ((TextView) row.findViewById(R.id.alert_item_title)).setText(kind.title);
        ((TextView) row.findViewById(R.id.alert_item_summary)).setText(summary);
        TextView badge = row.findViewById(R.id.alert_item_level);
        badge.setText(kind.levelText());
        String[] fill = {"#E7EAF0", "#FFE1E4", "#FFF0D1", "#DDEBFF"};
        String[] ink = {"#263245", "#841C34", "#6B420B", "#174477"};
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(8));
        background.setColor(Color.parseColor(fill[kind.level]));
        badge.setBackground(background);
        badge.setTextColor(Color.parseColor(ink[kind.level]));
        row.setOnClickListener(view -> action.run());
        parent.addView(row);
    }

    private void showDetail(AlertRepository.Kind kind, AlertRepository.Event event) {
        View body = getLayoutInflater().inflate(R.layout.dialog_alert_detail, null);
        TextView meta = body.findViewById(R.id.alert_detail_meta);
        TextView evidence = body.findViewById(R.id.alert_detail_evidence);
        String level = kind.levelText() + " · " + kind.source;
        if (event == null) {
            meta.setText(level);
            evidence.setText(kind.rule);
        } else {
            meta.setText(level + "\n" + event.occurredAt + "（北京时间）"
                    + "\n设备 " + event.deviceId + " · " + event.status);
            evidence.setText(event.evidence.isEmpty() ? "事件采样详情待接入\n\n" + kind.rule : event.evidence);
        }
        body.findViewById(R.id.alert_detail_data).setOnClickListener(view -> showCareData());
        body.findViewById(R.id.alert_detail_photos).setOnClickListener(view -> showPhotos(event));
        present(new MaterialAlertDialogBuilder(requireContext()).setTitle(kind.title)
                .setView(body).setPositiveButton("关闭", null));
    }

    private void showCareData() {
        String data = repository.latestCareData();
        if (data.isEmpty()) {
            data = "运动姿态（MPU6050）　待接入\n"
                    + "空气相对污染指数（MQ135）　待接入\n"
                    + "设备心跳与六轴数据时间　待接入\n\n"
                    + "温度与湿度（DHT22）　待接入\n"
                    + "最近 GPS 位置与时间　待接入\n"
                    + "DeepSeek 辅助研判　待接入";
        }
        message("老人数据", data);
    }

    private void showPhotos(@Nullable AlertRepository.Event event) {
        List<AlertRepository.Photo> photos = repository.photos(event);
        if (photos.isEmpty()) {
            message("现场照片", event == null ? "照片数据待接入。"
                    : "该报警暂无可查看的关联照片。\n照片在上传后按事件关联。");
            return;
        }
        CharSequence[] names = new CharSequence[photos.size()];
        for (int i = 0; i < names.length; i++) {
            AlertRepository.Photo photo = photos.get(i);
            names[i] = photo.time + " · " + photo.source;
        }
        present(new MaterialAlertDialogBuilder(requireContext()).setTitle("现场照片")
                .setItems(names, (popup, which) -> showPhoto(photos.get(which)))
                .setNegativeButton("关闭", null));
    }

    private void showPhoto(AlertRepository.Photo photo) {
        ImageView image = new ImageView(requireContext());
        image.setImageBitmap(photo.bitmap);
        image.setContentDescription(photo.time + " · " + photo.source);
        image.setMinimumHeight(dp(220));
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        present(new MaterialAlertDialogBuilder(requireContext()).setTitle(photo.time)
                .setView(image).setPositiveButton("关闭", null));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void message(String title, String text) {
        present(new MaterialAlertDialogBuilder(requireContext()).setTitle(title).setMessage(text)
                .setPositiveButton("关闭", null));
    }

    private void present(MaterialAlertDialogBuilder builder) {
        if (dialog != null) dialog.dismiss();
        dialog = builder.show();
    }
}
