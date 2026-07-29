package cxyonly.fans;

import android.app.Application;
import android.content.Context;

/**
 * Application 入口：初始化全局配置
 */
public class CxyApplication extends Application {

    private static Context appContext;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();

    }

    public static Context getAppContext() {
        return appContext;
    }
}
