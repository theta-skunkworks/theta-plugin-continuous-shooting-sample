package com.theta360.continuousshooting;

import android.app.Application;

import timber.log.Timber;

public class ContinuousShooting extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        if (BuildConfig.DEBUG) {
            Timber.plant(new Timber.DebugTree());
        }
    }

}
