package com.example.unlimitedpossibilities12.messenger.notife;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

/**
 * Доступны ли push-уведомления на этом телефоне.
 *
 * Push — основной способ доставки: он будит приложение, когда оно закрыто, и
 * не требует постоянного соединения. Но на прошивках без сервисов Google
 * (F-Droid, часть китайских аппаратов) их нет — там остаётся прежний путь:
 * постоянно работающий CallService.
 */
public final class PushAvailability {
    private static final String TAG = "PushAvailability";
    private static Boolean cached;

    private PushAvailability() {}

    public static boolean isAvailable(Context context) {
        if (cached != null) return cached;
        try {
            int status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
            cached = status == ConnectionResult.SUCCESS;
        } catch (Throwable t) {
            cached = false;
        }
        Log.d(TAG, "Push " + (cached ? "доступны" : "недоступны — работаем на постоянном соединении"));
        return cached;
    }
}
