package com.zhihuyitu.care;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.Predicate;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class LocationPlaceTest {
    @Test public void opensSearchAndKeepsValidationOnTheSameSheet() {
        assumeTrue(Build.VERSION.SDK_INT >= 28);
        assumeTrue(BuildConfig.LOCATION_PHONE_TEST);
        Instrumentation runner = InstrumentationRegistry.getInstrumentation();
        UiAutomation automation = runner.getUiAutomation();
        AccessibilityServiceInfo service = automation.getServiceInfo();
        service.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(service);
        String pkg = runner.getTargetContext().getPackageName();
        automation.grantRuntimePermission(pkg, Manifest.permission.ACCESS_COARSE_LOCATION);

        Intent launch = new Intent(runner.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Activity activity = runner.startActivitySync(launch);
        try {
            click(find(automation, "locationFragment", AccessibilityNodeInfo::isClickable));
            find(automation, "location_history", node -> contains(node, "今日轨迹"));
            click(find(automation, "location_places", AccessibilityNodeInfo::isClickable));
            find(automation, "place_keyword", AccessibilityNodeInfo::isEditable);
            click(find(automation, "place_search", AccessibilityNodeInfo::isClickable));
            find(automation, "place_result", node -> contains(node, "请输入地点名称"));
            find(automation, "place_keyword", AccessibilityNodeInfo::isEditable);
        } finally {
            runner.runOnMainSync(activity::finish);
        }
    }

    private boolean contains(AccessibilityNodeInfo node, String text) {
        return node.getText() != null && node.getText().toString().contains(text);
    }

    private void click(AccessibilityNodeInfo node) {
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
    }

    private AccessibilityNodeInfo find(UiAutomation automation, String id, Predicate<AccessibilityNodeInfo> matches) {
        long timeout = SystemClock.uptimeMillis() + 8000;
        do {
            AccessibilityNodeInfo root = automation.getRootInActiveWindow();
            if (root != null) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId("com.zhihuyitu.care:id/" + id)) {
                    if (matches.test(node)) return node;
                }
            }
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < timeout);
        throw new AssertionError("Expected view " + id);
    }
}
