package com.trellis.viewer;

import android.app.Application;

import com.trellis.viewer.util.ThemePrefs;

public class TrellisApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ThemePrefs.applyNightMode(this);
    }
}
