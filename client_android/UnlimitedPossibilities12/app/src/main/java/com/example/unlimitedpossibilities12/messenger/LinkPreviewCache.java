package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import com.example.unlimitedpossibilities12.api.Message;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Превью ссылок: храним разобранное, чтобы не разбирать его заново.
 *
 * Сообщения приходят с сервера новыми объектами, поэтому разобранное превью
 * терялось при каждом входе в чат — и секунду-другую была видна голая ссылка.
 * Держим его по адресу: в памяти и на диске. Для YouTube вдобавок не качаем
 * страницу целиком — она весит мегабайты, — а спрашиваем короткий ответ oEmbed.
 */
public final class LinkPreviewCache {

    private static final String TAG = "LinkPreviewCache";
    private static final String STORE = "link_previews";
    /** Превью живёт две недели: заголовки страниц меняются редко. */
    private static final long TTL_MS = 14L * 24 * 60 * 60 * 1000;
    /** Чтобы файл настроек не рос без конца. */
    private static final int MAX_ENTRIES = 300;

    private static final Pattern YOUTUBE_ID = Pattern.compile(
            "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:[^#]*&)?v=|embed/|shorts/|live/|v/))([A-Za-z0-9_-]{11})");

    private static final Map<String, Message.LinkPreviewData> MEMORY = new ConcurrentHashMap<>();

    private LinkPreviewCache() {
    }

    /** Готовое превью для адреса или null, если его ещё не разбирали. */
    public static Message.LinkPreviewData get(Context context, String url) {
        Message.LinkPreviewData cached = MEMORY.get(url);
        if (cached != null) return cached;

        try {
            String raw = store(context).getString(url, null);
            if (raw == null) return null;

            JSONObject json = new JSONObject(raw);
            if (System.currentTimeMillis() - json.optLong("ts") > TTL_MS) {
                store(context).edit().remove(url).apply();
                return null;
            }

            Message.LinkPreviewData data = new Message.LinkPreviewData();
            data.title = json.optString("title");
            data.description = json.optString("description");
            data.imageUrl = json.optString("imageUrl");
            data.url = json.optString("url", url);
            MEMORY.put(url, data);
            return data;
        } catch (Exception e) {
            Log.w(TAG, "Не удалось прочитать превью из кэша: " + e.getMessage());
            return null;
        }
    }

    /** Запомнить разобранное превью. */
    public static void put(Context context, String url, Message.LinkPreviewData data) {
        if (data == null) return;
        MEMORY.put(url, data);

        try {
            SharedPreferences prefs = store(context);
            if (prefs.getAll().size() >= MAX_ENTRIES) prefs.edit().clear().apply();

            JSONObject json = new JSONObject();
            json.put("title", data.title == null ? "" : data.title);
            json.put("description", data.description == null ? "" : data.description);
            json.put("imageUrl", data.imageUrl == null ? "" : data.imageUrl);
            json.put("url", data.url == null ? url : data.url);
            json.put("ts", System.currentTimeMillis());
            prefs.edit().putString(url, json.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Не удалось сохранить превью: " + e.getMessage());
        }
    }

    /**
     * Разобрать страницу. Вызывать только из фонового потока; результат сразу
     * попадает в кэш. Возвращает null, если ничего не вышло.
     */
    public static Message.LinkPreviewData fetch(Context context, String url) {
        Message.LinkPreviewData ready = get(context, url);
        if (ready != null) return ready;

        Message.LinkPreviewData data = isYouTube(url) ? fromYouTube(url) : fromPage(url);
        if (data != null) put(context, url, data);
        return data;
    }

    private static boolean isYouTube(String url) {
        return YOUTUBE_ID.matcher(url).find();
    }

    /**
     * YouTube отдаёт заголовок и миниатюру коротким ответом oEmbed — это
     * килобайты вместо целой страницы с плеером.
     */
    private static Message.LinkPreviewData fromYouTube(String url) {
        Matcher matcher = YOUTUBE_ID.matcher(url);
        String videoId = matcher.find() ? matcher.group(1) : null;

        Message.LinkPreviewData data = new Message.LinkPreviewData();
        data.url = url;
        data.title = "YouTube";
        data.description = "";
        // Миниатюра известна по одному лишь номеру видео — она появится, даже
        // если ответ с заголовком не дойдёт.
        data.imageUrl = videoId == null ? "" : "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";

        try {
            String oembed = "https://www.youtube.com/oembed?url="
                    + URLEncoder.encode(url, "UTF-8") + "&format=json";
            JSONObject json = new JSONObject(readUrl(oembed));

            String title = json.optString("title");
            if (!TextUtils.isEmpty(title)) data.title = title;
            data.description = json.optString("author_name", "");

            String thumb = json.optString("thumbnail_url");
            if (!TextUtils.isEmpty(thumb)) data.imageUrl = thumb;
        } catch (Exception e) {
            Log.w(TAG, "oEmbed не ответил для " + url + ": " + e.getMessage());
            if (videoId == null) return null;
        }
        return data;
    }

    /** Обычная страница: читаем og-метки, как и раньше. */
    private static Message.LinkPreviewData fromPage(String url) {
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(10000)
                    .get();

            Message.LinkPreviewData data = new Message.LinkPreviewData();
            data.url = url;
            data.title = doc.selectFirst("meta[property=og:title]") != null
                    ? doc.selectFirst("meta[property=og:title]").attr("content") : doc.title();

            String desc = doc.selectFirst("meta[property=og:description]") != null
                    ? doc.selectFirst("meta[property=og:description]").attr("content") : "";
            if (desc.isEmpty() && doc.selectFirst("meta[name=description]") != null) {
                desc = doc.selectFirst("meta[name=description]").attr("content");
            }
            data.description = desc;

            data.imageUrl = doc.selectFirst("meta[property=og:image]") != null
                    ? doc.selectFirst("meta[property=og:image]").attr("content") : "";
            return data;
        } catch (Exception e) {
            Log.w(TAG, "Не удалось разобрать " + url + ": " + e.getMessage());
            return null;
        }
    }

    private static String readUrl(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        try {
            StringBuilder body = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            } finally {
                reader.close();
            }
            return body.toString();
        } finally {
            connection.disconnect();
        }
    }

    private static SharedPreferences store(Context context) {
        return context.getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }
}
