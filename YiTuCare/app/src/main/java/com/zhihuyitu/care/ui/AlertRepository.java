package com.zhihuyitu.care.ui;

import android.content.Context;
import android.graphics.Bitmap;

import com.zhihuyitu.care.BuildConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class AlertRepository {
    enum Kind {
        FALL("疑似跌倒", 1, "设备端规则",
                "4 秒内需同时具备冲击、落地静止、姿态变化和附加动作证据。\n\n"
                + "冲击：合加速度 ≥ 2.5g，或低于 0.5g 持续至少 60ms 后，1 秒内出现 ≥ 1.8g 冲击。\n"
                + "静止：冲击 0.2 秒后，合加速度回到 1g ± 0.20g，角速度 ≤ 20°/s，采样跳变 ≤ 0.12g，持续至少 1.2 秒。\n"
                + "姿态：重力方向改变 ≥ 40°。附加证据为失重、累计旋转 ≥ 25° 或重力方向改变 ≥ 60°，满足一项即可。\n\n"
                + "触发后冷却 8 秒。恢复直立、倾角低于 30° 持续 1 秒后解除倾斜报警锁存。"),
        MODEL_FALL("六轴模型疑似跌倒", 1, "电脑端模型",
                "50Hz 六轴数据每 150 帧组成 3 秒窗口。模型使用加速度和角速度模值、变化率及重力方向投影等特征。"
                + "\n\n新版需求使用 TCN，模型分数达到当前阈值时产生记录，默认阈值为 0.5。事件详情应保留模型版本、分数和当时阈值。"),
        TILT("持续异常倾斜", 2, "设备端规则",
                "未处于跌倒判定流程时，倾角 ≥ 45° 且持续超过 1.5 秒。持续倾斜时约每 5 秒产生一次告警，角度回落后解除。"),
        AIR("空气持续超标", 2, "设备端规则",
                "MQ135 基于开机校准基线计算 0～100 相对污染指数。指数 ≥ 60 且持续超过 6 秒触发一次。"
                + "指数降到 ≤ 40 并保持稳定后解除，之后可再次触发。"),
        OFFLINE("设备持续离线", 3, "设备通信心跳",
                "超过 30 秒未收到该设备任何上报时提醒，收到数据后解除。本状态变化只影响设备离线提醒。"),
        IMU_GAP("六轴数据中断", 3, "六轴数据心跳",
                "设备仍在线，但超过 15 秒未收到六轴数据时提醒。六轴数据恢复后解除该提醒。"),
        RANGE("超出安全范围", 0, "本机范围提醒",
                "首次越过缓冲区外沿时提醒一次。持续回到安全范围达到设置时长后，恢复下一次提醒。");

        final String title;
        final int level;
        final String source;
        final String rule;

        Kind(String title, int level, String source, String rule) {
            this.title = title;
            this.level = level;
            this.source = source;
            this.rule = rule;
        }

        String levelText() {
            switch (level) {
                case 1: return "一级 · 紧急警告";
                case 2: return "二级 · 重要警告";
                case 3: return "三级 · 设备提醒";
                default: return "范围提醒";
            }
        }
    }

    static final class Event {
        final Kind kind;
        final String deviceId;
        final String bootId;
        final String eventKey;
        final String occurredAt;
        final String status;
        final String evidence;

        Event(Kind kind, String deviceId, String bootId, String eventKey, String occurredAt,
              String status, String evidence) {
            this.kind = kind;
            this.deviceId = deviceId;
            this.bootId = bootId;
            this.eventKey = eventKey;
            this.occurredAt = occurredAt;
            this.status = status;
            this.evidence = evidence;
        }
    }

    static final class Photo {
        final Bitmap bitmap;
        final String time;
        final String source;

        Photo(Bitmap bitmap, String time, String source) {
            this.bitmap = bitmap;
            this.time = time;
            this.source = source;
        }
    }

    private final Context context;

    AlertRepository(Context context) {
        this.context = context;
    }

    List<Event> events() {
        List<Event> events = new ArrayList<>(authorizedEvents());
        for (String time : LocationFence.alerts(context)) {
            events.add(new Event(Kind.RANGE, BuildConfig.LOCATION_PHONE_TEST ? "手机实测" : BuildConfig.LOCATION_DEVICE_ID,
                    "", time, time, "本机记录", ""));
        }
        events.sort(Comparator.comparing((Event event) -> event.occurredAt).reversed());
        return events;
    }

    private List<Event> authorizedEvents() {
        // TODO FR-005/008 PG-08: Map authorized Flask events and their source, status and immutable event keys.
        return Collections.emptyList();
    }

    String latestCareData() {
        // TODO FR-003/006 PG-09: Read authorized GPS, MPU6050, MQ135, DHT22, heartbeats and AI evidence.
        return "";
    }

    List<Photo> photos(Event event) {
        // TODO FR-010/013: Fetch authorized images on a worker. For device events filter by deviceId, bootId
        // and eventKey (event sequence) before loading JPEG. Null event means the authorized device's gallery.
        return Collections.emptyList();
    }
}
