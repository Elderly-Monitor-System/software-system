package com.zhihuyitu.care.ui;

import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.zhihuyitu.care.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class LocationPlaceSheet {
    static final int PAGE_SIZE = 25;
    static final int MAX_PAGE = 100;
    private final LocationFragment host;
    private final LocationMap map;
    private final Handler handler;
    private final Map<String, Result> matches = new LinkedHashMap<>();
    private final List<Result> ordered = new ArrayList<>();
    private Dialog sheet;
    private ListView results;
    private ResultAdapter adapter;
    private TextView status;
    private LocationRepository.Point origin;
    private LocationRepository.Point currentOrigin;
    private String keyword;
    private int token;
    private int nextPage;
    private boolean loading;
    private boolean hasMore;
    private boolean nearby;
    private boolean scrolling;

    LocationPlaceSheet(LocationFragment host, LocationMap map, Handler handler) {
        this.host = host;
        this.map = map;
        this.handler = handler;
    }

    void open(LocationRepository.Point currentLocation) {
        sheet = new Dialog(host.requireContext());
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sheet.setCanceledOnTouchOutside(true);
        currentOrigin = currentLocation == null ? null : LocationRepository.mapCoordinate(currentLocation);
        origin = currentOrigin;
        View body = host.getLayoutInflater().inflate(R.layout.dialog_location_places, null);
        sheet.setContentView(body);
        EditText input = body.findViewById(R.id.place_keyword);
        View search = body.findViewById(R.id.place_search);
        status = body.findViewById(R.id.place_result);
        results = body.findViewById(R.id.place_results);
        adapter = new ResultAdapter(host.getLayoutInflater());
        results.setAdapter(adapter);
        search.setOnClickListener(view -> {
            InputMethodManager keyboard = host.requireContext().getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
            input.clearFocus();
            begin(input.getText().toString().trim());
        });
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            search.performClick();
            return true;
        });
        results.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                scrolling = state != SCROLL_STATE_IDLE;
                if (scrolling && view.getLastVisiblePosition() >= ordered.size() - 3) next();
            }

            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                if (scrolling && total > 0 && first + visible >= total - 2) next();
            }
        });
        sheet.setOnDismissListener(dialog -> {
            ++token;
            loading = false;
            hasMore = false;
            scrolling = false;
            matches.clear();
            ordered.clear();
            sheet = null;
            status = null;
            results = null;
            adapter = null;
        });
        sheet.setOnShowListener(dialog -> {
            Window window = sheet.getWindow();
            if (window == null) return;
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            resize();
            host.dimBackground(sheet);
            body.setTranslationY(32 * host.getResources().getDisplayMetrics().density);
            body.animate().translationY(0).setDuration(180).start();
        });
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        setStatus("输入关键词查找地点");
        sheet.show();
    }

    void dismiss() {
        if (sheet != null) sheet.dismiss();
    }

    void updateLocation(LocationRepository.Point point) {
        if (sheet == null) return;
        currentOrigin = point == null ? null : LocationRepository.mapCoordinate(point);
        if (origin == null && currentOrigin != null) {
            origin = currentOrigin;
            if (!ordered.isEmpty()) refreshRows();
        }
    }

    private void begin(String input) {
        ++token;
        keyword = input;
        origin = currentOrigin;
        matches.clear();
        ordered.clear();
        adapter.notifyDataSetChanged();
        loading = false;
        scrolling = false;
        nextPage = 1;
        hasMore = false;
        resize();
        if (input.isEmpty()) {
            setStatus("请输入地点名称");
        } else if (!host.isMapReady()) {
            setStatus("高德地图加载后可查询地点");
        } else {
            nearby = origin != null;
            hasMore = true;
            next();
        }
    }

    private void next() {
        if (sheet == null || loading || !hasMore) return;
        int request = ++token;
        loading = true;
        setStatus(nextPage == 1 ? "正在搜索…" : ordered.size() + " 条 · 正在加载更多…");
        map.search(request, nextPage, PAGE_SIZE, keyword, nearby ? origin : null);
        handler.postDelayed(() -> {
            if (sheet != null && request == token && loading) searchFailed("地点查询超时，点此重试");
        }, 15000);
    }

    void onPlaces(JSONObject data) {
        if (sheet == null || !loading || data.optInt("request", -1) != token) return;
        loading = false;
        if (data.optBoolean("failed")) {
            if (nearby && nextPage == 1) {
                nearby = false;
                next();
            } else {
                searchFailed("地点查询失败，点此重试");
            }
            return;
        }
        JSONArray rows = data.optJSONArray("locations");
        int added = 0;
        if (rows != null) {
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row == null) continue;
                try {
                    Result place = new Result(row);
                    if (!matches.containsKey(place.key)) {
                        matches.put(place.key, place);
                        added++;
                    }
                } catch (JSONException | IllegalArgumentException invalid) {
                    // A result without a usable coordinate cannot be sorted by distance.
                }
            }
        }
        hasMore = data.optBoolean("more") && nextPage < MAX_PAGE && rows != null && rows.length() > 0;
        if (!hasMore && nearby) {
            nearby = false;
            nextPage = 1;
            hasMore = true;
        } else nextPage++;
        refreshRows();
        // A page can contain duplicate POIs. Advance until the user has new rows or the provider is done.
        if (added == 0 && hasMore && ordered.isEmpty()) next();
    }

    private void searchFailed(String message) {
        loading = false;
        setStatus(message);
        status.setOnClickListener(view -> next());
    }

    private void refreshRows() {
        View first = results.getChildAt(0);
        int firstIndex = results.getFirstVisiblePosition();
        Result anchor = first != null && firstIndex < ordered.size() ? ordered.get(firstIndex) : null;
        int offset = first == null ? 0 : first.getTop();
        ordered.clear();
        ordered.addAll(matches.values());
        sortByDistance(ordered, origin);
        adapter.notifyDataSetChanged();
        if (anchor != null && firstIndex > 0) results.setSelectionFromTop(ordered.indexOf(anchor), offset);

        if (ordered.isEmpty()) setStatus("没有找到匹配地点");
        else {
            String order = origin == null ? " · 等待手机定位排序" : " · 按距离排序";
            setStatus(ordered.size() + " 条" + order + (hasMore ? " · 下滑或点此加载" : ""));
            if (hasMore) status.setOnClickListener(view -> next());
        }
        resize();
    }

    static void sortByDistance(List<Result> entries, LocationRepository.Point origin) {
        for (Result result : entries) {
            result.distance = origin == null ? Double.NaN : LocationRepository.distanceMeters(origin, result.coordinate);
        }
        entries.sort(Comparator.comparingDouble(result -> result.distance));
    }

    private void setStatus(String message) {
        status.setText(message);
        status.setOnClickListener(null);
        status.setClickable(false);
    }

    private void resize() {
        Window window = sheet == null ? null : sheet.getWindow();
        if (window == null) return;
        View root = host.requireView().getRootView();
        int availableHeight = root.getHeight();
        if (availableHeight == 0) availableHeight = host.getResources().getDisplayMetrics().heightPixels;
        int availableWidth = root.getWidth();
        if (availableWidth == 0) availableWidth = host.getResources().getDisplayMetrics().widthPixels;
        float density = host.getResources().getDisplayMetrics().density;
        int desiredHeight = Math.round((124 + 61 * Math.min(8, ordered.size())) * density);
        int width = Math.min(availableWidth, Math.round(640 * density));
        window.setLayout(width, Math.min(Math.round(availableHeight * 0.84f), desiredHeight));
    }

    static final class Result {
        final String key;
        final String name;
        final String address;
        final LocationRepository.Point coordinate;
        double distance;

        Result(JSONObject data) throws JSONException {
            this(data.optString("id"), data.getString("name"), data.optString("address"),
                    new LocationRepository.Point(data.getDouble("longitude"), data.getDouble("latitude"), null));
        }

        Result(String id, String name, String address, LocationRepository.Point coordinate) {
            key = id.isEmpty() ? name + "|" + coordinate.longitude + "|" + coordinate.latitude : id;
            this.name = name;
            this.address = address;
            this.coordinate = coordinate;
        }

        String distanceLabel() {
            if (Double.isNaN(distance)) return "";
            if (distance < 1000) return Math.round(distance) + " 米";
            return String.format(Locale.CHINA, "%.1f 公里", distance / 1000);
        }
    }

    private final class ResultAdapter extends BaseAdapter {
        private final LayoutInflater inflater;

        ResultAdapter(LayoutInflater inflater) { this.inflater = inflater; }
        @Override public int getCount() { return ordered.size(); }
        @Override public Result getItem(int index) { return ordered.get(index); }
        @Override public long getItemId(int index) { return index; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int index) { return false; }

        @Override public View getView(int index, View row, ViewGroup parent) {
            if (row == null) row = inflater.inflate(R.layout.item_location_place, parent, false);
            Result place = getItem(index);
            ((TextView) row.findViewById(R.id.place_name)).setText(place.name);
            TextView address = row.findViewById(R.id.place_address);
            address.setText(place.address);
            address.setVisibility(place.address.isEmpty() ? View.GONE : View.VISIBLE);
            TextView distance = row.findViewById(R.id.place_distance);
            String label = place.distanceLabel();
            distance.setText(label);
            distance.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
            return row;
        }
    }
}
