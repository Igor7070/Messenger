package com.example.unlimitedpossibilities12.api;

import android.content.Context;
import android.content.SharedPreferences;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/**
 * Токен доступа к API. Выдаётся сервером при логине/регистрации (поле token
 * в ответе), хранится в SharedPreferences и подставляется во все HTTP-запросы
 * к бэкенду через {@link #interceptor()} и в STOMP CONNECT через {@link #bearer()}.
 */
public final class AuthToken {
    private static final String PREFS = "auth";
    private static final String KEY = "token";
    private static final String API_HOST = "unlimitedpossibilities12.org";

    private static volatile String cached;
    private static Context appContext;

    private AuthToken() {}

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        cached = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
    }

    public static String get() {
        if (cached == null && appContext != null) {
            cached = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
        }
        return cached;
    }

    public static void set(Context context, String token) {
        if (appContext == null) appContext = context.getApplicationContext();
        cached = token;
        SharedPreferences.Editor e = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (token == null) e.remove(KEY); else e.putString(KEY, token);
        e.apply();
    }

    public static void clear(Context context) {
        set(context, null);
    }

    /** Значение для заголовка Authorization или null, если токена нет. */
    public static String bearer() {
        String t = get();
        return t == null ? null : "Bearer " + t;
    }

    /** OkHttp-перехватчик: добавляет Authorization к запросам на бэкенд. */
    public static Interceptor interceptor() {
        return chain -> {
            Request original = chain.request();
            String bearer = bearer();
            if (bearer == null
                    || !original.url().host().endsWith(API_HOST)
                    || original.header("Authorization") != null) {
                return chain.proceed(original);
            }
            return chain.proceed(original.newBuilder().header("Authorization", bearer).build());
        };
    }

    /** Общий OkHttpClient с перехватчиком — вместо разбросанных new OkHttpClient(). */
    public static OkHttpClient httpClient() {
        return new OkHttpClient.Builder().addInterceptor(interceptor()).build();
    }
}
