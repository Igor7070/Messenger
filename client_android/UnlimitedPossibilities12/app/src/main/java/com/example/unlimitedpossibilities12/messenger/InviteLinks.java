package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.Intent;

/**
 * Ссылка-приглашение в нашу же группу.
 *
 * Android 12 и новее отдаёт веб-ссылку, открытую из приложения, сразу браузеру,
 * если домен не подтвердил родство с приложением, — даже не спрашивая. Поэтому
 * приглашение, присланное в переписку, уводило человека в браузер, хотя
 * приложение, в котором он эту ссылку и нажал, умеет открыть её само.
 *
 * Свои ссылки мы узнаём по виду и обрабатываем сами, не спрашивая систему.
 */
public final class InviteLinks {

    private static final String HOST = "unlimitedpossibilities12.org";
    private static final String PATH = "/invite/";

    private InviteLinks() {
    }

    public static boolean isOurs(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains(HOST) && lower.contains(PATH);
    }

    /**
     * @return true — приглашение наше и уже открыто в приложении.
     */
    public static boolean open(Context context, String url) {
        if (context == null || !isOurs(url)) return false;
        Intent intent = new Intent(context, MessengerMainActivity.class);
        intent.setData(android.net.Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
        return true;
    }
}
