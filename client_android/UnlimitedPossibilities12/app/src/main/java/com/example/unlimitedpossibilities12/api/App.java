package com.example.unlimitedpossibilities12.api;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.example.unlimitedpossibilities12.messenger.notife.CallService;

public class App extends Application {
    private static final String TAG = "App";
    private int activityCount = 0; // Счетчик активных активностей
    private int currentUserId = -1;
    private String currentUsername;
    private Activity currentActivity;

    @Override
    public void onCreate() {
        super.onCreate();
        AuthToken.init(this); // токен доступа к API

        // Делаем верхнюю статус-бар тёмной во всём приложении
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    // Тёмный цвет статус-бара
                    activity.getWindow().setStatusBarColor(Color.parseColor("#0F172A"));

                    // Светлые иконки (время, батарея, Wi-Fi и т.д.)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        View decorView = activity.getWindow().getDecorView();
                        decorView.setSystemUiVisibility(
                                decorView.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        );
                    }
                }

                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityResumed(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
        }

        // Глобальный цвет нижней системной панели для всего приложения
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                    activity.getWindow().setNavigationBarColor(Color.parseColor("#0F172A"));

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        View decorView = activity.getWindow().getDecorView();
                        int flags = decorView.getSystemUiVisibility();
                        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                        decorView.setSystemUiVisibility(flags);
                    }
                }

                @Override public void onActivityStarted(Activity activity) {}
                @Override public void onActivityResumed(Activity activity) {}
                @Override public void onActivityPaused(Activity activity) {}
                @Override public void onActivityStopped(Activity activity) {}
                @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
                @Override public void onActivityDestroyed(Activity activity) {}
            });
        }

        currentUserId = getSharedPreferences("prefs", MODE_PRIVATE).getInt("userId", -1);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                currentActivity = activity;
                Log.d(TAG, "Activity created: " + activity.getClass().getSimpleName());
            }

            @Override
            public void onActivityStarted(Activity activity) {
                if (activityCount == 0) {
                    // Приложение на экране — поднимаем постоянное соединение:
                    // мгновенная доставка, статусы «в сети», набор текста
                    if (currentUserId != -1) startCallService("com.example.unlimitedpossibilities12.APP_FOREGROUND");
                    Log.d(TAG, "Application moved to foreground");
                }
                activityCount++;
                currentActivity = activity;
                Log.d(TAG, "Activity started: " + activity.getClass().getSimpleName() + ", count: " + activityCount);
            }

            @Override
            public void onActivityResumed(Activity activity) {
                currentActivity = activity;
                Log.d(TAG, "Activity resumed: " + activity.getClass().getSimpleName());
            }

            @Override
            public void onActivityPaused(Activity activity) {
                Log.d(TAG, "Activity paused: " + activity.getClass().getSimpleName());
            }

            @Override
            public void onActivityStopped(Activity activity) {
                activityCount--;
                if (activityCount == 0) {
                    // Ушли в фон. Если push доступны — соединение больше не держим,
                    // сообщения и звонки разбудят приложение сами. Это экономит
                    // батарею и убирает постоянное уведомление сервиса.
                    if (com.example.unlimitedpossibilities12.messenger.notife.PushAvailability.isAvailable(App.this)
                            && !CallService.isCallInProgress()) {
                        Intent intent = new Intent(App.this, CallService.class);
                        intent.putExtra("action", "APP_BACKGROUND_STOP");
                        startService(intent);
                        Log.d(TAG, "Application moved to background — сервис остановлен, работают push");
                    } else {
                        Intent intent = new Intent(App.this, CallService.class);
                        intent.setAction("com.example.unlimitedpossibilities12.APP_BACKGROUND");
                        startService(intent);
                        Log.d(TAG, "Application moved to background — соединение сохранено");
                    }
                }
                Log.d(TAG, "Activity stopped: " + activity.getClass().getSimpleName() + ", count: " + activityCount);
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

            @Override
            public void onActivityDestroyed(Activity activity) {
                if (currentActivity == activity) {
                    currentActivity = null;
                }
                Log.d(TAG, "Activity destroyed: " + activity.getClass().getSimpleName());
            }
        });

        // Сервис поднимаем при входе пользователя на экран (см. onActivityStarted),
        // а на телефонах без push — сразу, как раньше: иначе сообщения не дойдут.
        if (currentUserId != -1
                && !com.example.unlimitedpossibilities12.messenger.notife.PushAvailability.isAvailable(this)) {
            startCallService(null);
            Log.d(TAG, "Push недоступны — CallService запущен постоянно, userId: " + currentUserId);
        }
    }

    /** Запустить CallService (постоянное соединение) с необязательным действием. */
    public void startCallService(String action) {
        try {
            Intent intent = new Intent(this, CallService.class);
            if (action != null) intent.setAction(action);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "Не удалось запустить CallService: " + e.getMessage());
        }
    }

    public boolean isAppInForeground() {
        return activityCount > 0;
    }

    public Activity getCurrentActivity() {
        return currentActivity;
    }

    public int getCurrentUserId() {
        return currentUserId;
    }

    public void setCurrentUserId(int userId) {
        this.currentUserId = userId;
        getSharedPreferences("prefs", MODE_PRIVATE).edit().putInt("userId", userId).apply();
        // Push-уведомления привязываются к пользователю: сообщаем серверу токен устройства
        if (userId > 0) {
            com.example.unlimitedpossibilities12.messenger.notife.PushMessagingService.registerCurrentToken(this);
        }
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    public void setCurrentUsername(String username) {
        this.currentUsername = username;
    }
}