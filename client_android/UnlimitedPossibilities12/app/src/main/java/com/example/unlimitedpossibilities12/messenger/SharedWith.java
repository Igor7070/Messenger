package com.example.unlimitedpossibilities12.messenger;

import com.example.unlimitedpossibilities12.api.Message;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Что накопилось в переписке с человеком: снимки, видео, файлы, ссылки,
 * голосовые. Те же правила разбора, что и в веб-клиенте, — чтобы счёт на
 * телефоне и в браузере сходился.
 *
 * Секретную переписку не трогаем: её содержимое зашифровано, и вскрывать его
 * ради подсчёта снимков карточка не должна.
 */
public final class SharedWith {

    public static final String PHOTOS = "photos";
    public static final String VIDEOS = "videos";
    public static final String FILES = "files";
    public static final String LINKS = "links";
    public static final String VOICES = "voices";

    private static final Pattern LINK = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);

    /** Одна находка: снимок, файл, ссылка или голосовое. */
    public static class Item {
        public final long id;
        public final String url;      // адрес файла или сама ссылка
        public final String name;     // имя файла или короткий адрес
        public final String timestamp;

        Item(long id, String url, String name, String timestamp) {
            this.id = id;
            this.url = url;
            this.name = name;
            this.timestamp = timestamp;
        }
    }

    private SharedWith() {
    }

    private static boolean isImage(String url) {
        return url != null && url.toLowerCase().matches(".*\\.(jpg|jpeg|png|gif|webp|bmp)$");
    }

    private static boolean isVideo(String url) {
        return url != null && url.toLowerCase().matches(".*\\.(mp4|webm|mov|mkv|avi)$");
    }

    private static String fileName(String url) {
        if (url == null) return "";
        String tail = url.substring(url.lastIndexOf('/') + 1);
        return tail.isEmpty() ? url : tail;
    }

    /** Разобрать историю переписки на разряды. */
    public static List<Item> pick(List<Message> messages, String kind) {
        List<Item> found = new ArrayList<>();
        if (messages == null) return found;

        for (Message message : messages) {
            String url = message.getFileUrl();
            boolean audio = "audio".equals(message.getMessageType());

            if (VOICES.equals(kind) && audio && url != null) {
                found.add(new Item(message.getId(), url, fileName(url), message.getTimestamp()));
            } else if (PHOTOS.equals(kind) && !audio && isImage(url)) {
                found.add(new Item(message.getId(), url, fileName(url), message.getTimestamp()));
            } else if (VIDEOS.equals(kind) && !audio && isVideo(url)) {
                found.add(new Item(message.getId(), url, fileName(url), message.getTimestamp()));
            } else if (FILES.equals(kind) && !audio && url != null && !isImage(url) && !isVideo(url)) {
                found.add(new Item(message.getId(), url, fileName(url), message.getTimestamp()));
            } else if (LINKS.equals(kind) && message.getContent() != null) {
                Matcher matcher = LINK.matcher(message.getContent());
                while (matcher.find()) {
                    String href = matcher.group();
                    found.add(new Item(message.getId(), href, shortLink(href), message.getTimestamp()));
                }
            }
        }

        // Новое сверху — как в любом списке такого рода
        found.sort(Comparator.comparing((Item item) -> item.timestamp == null ? "" : item.timestamp).reversed());
        return found;
    }

    /** Адрес без «http://» и длинного хвоста: в строку целиком он не влезает. */
    public static String shortLink(String href) {
        if (href == null) return "";
        String text = href.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        int slash = text.indexOf('/');
        if (slash > 0 && slash < text.length() - 1) return text.substring(0, slash) + "/…";
        return slash > 0 ? text.substring(0, slash) : text;
    }
}
