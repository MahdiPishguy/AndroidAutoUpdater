package com.aspsine.androidappupdater.demo;

import android.app.Application;
import android.content.Context;

/**
 * Created by mahdi on 8/2/2017 AD.
 */

public class APP extends Application {
    private static Context                    context;

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
    }

    public static Context getContext() {
        return context;
    }
}
