package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * Высота снимков в переписке — чтобы лента не дёргалась.
 *
 * Ширина у снимка одна на всех, а высота зависит от самого снимка и
 * становится известна только после загрузки. До того в строке стоит
 * невысокая заглушка, и в тот миг, когда снимок приходит, строка
 * подрастает — лента рывком съезжает под пальцем.
 *
 * Поэтому высоту каждого снимка запоминаем по его ссылке и в следующий
 * раз занимаем место заранее. Незнакомому снимку отводим квадрат: это
 * ближе к правде, чем низкая заглушка.
 */
final class ВысотыСнимков {

    private static final String ХРАНИЛИЩЕ = "image_heights";

    /** Ширина снимка в разметке (item_message.xml). */
    private static final int ШИРИНА_DP = 252;
    private static final int НАИМЕНЬШАЯ_DP = 120;
    private static final int НАИБОЛЬШАЯ_DP = 420;

    private ВысотыСнимков() {
    }

    private static SharedPreferences хранилище(Context где) {
        return где.getSharedPreferences(ХРАНИЛИЩЕ, Context.MODE_PRIVATE);
    }

    private static int вПикселях(Context где, int dp) {
        return Math.round(dp * где.getResources().getDisplayMetrics().density);
    }

    /** Занять под снимок столько же места, сколько он займёт после загрузки. */
    static void занятьМесто(Context где, View снимок, String ссылка) {
        if (снимок == null || ссылка == null) return;
        int dp = хранилище(где).getInt(ссылка, 0);
        if (dp <= 0) dp = ШИРИНА_DP;   // незнакомый — считаем квадратным
        поставитьВысоту(где, снимок, dp);
    }

    /** Запомнить, какой снимок оказался на самом деле, и поправить место. */
    static void запомнить(Context где, View снимок, String ссылка, Drawable пришедший) {
        if (снимок == null || ссылка == null || пришедший == null) return;
        int ш = пришедший.getIntrinsicWidth(), в = пришедший.getIntrinsicHeight();
        if (ш <= 0 || в <= 0) return;
        int dp = Math.round(ШИРИНА_DP * (float) в / ш);
        dp = Math.max(НАИМЕНЬШАЯ_DP, Math.min(НАИБОЛЬШАЯ_DP, dp));
        хранилище(где).edit().putInt(ссылка, dp).apply();
        поставитьВысоту(где, снимок, dp);
    }

    private static void поставитьВысоту(Context где, View снимок, int dp) {
        ViewGroup.LayoutParams мера = снимок.getLayoutParams();
        if (мера == null) return;
        int высота = вПикселях(где, dp);
        if (мера.height == высота) return;
        мера.height = высота;
        снимок.setLayoutParams(мера);
    }
}
