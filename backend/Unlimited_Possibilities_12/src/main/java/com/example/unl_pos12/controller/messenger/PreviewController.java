package com.example.unl_pos12.controller.messenger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class PreviewController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Ключ YouTube Data API: берётся из переменной окружения YOUTUBE_API_KEY.
     *
     * Без него остаётся прежний порядок — oEmbed, затем страница видео. Но
     * YouTube отвечает отказом и на то, и на другое, когда спрашивают с адреса
     * дата-центра, поэтому для веб-клиента название берём здесь.
     */
    @org.springframework.beans.factory.annotation.Value("${youtube.api.key:}")
    private String youtubeApiKey;

    /**
     * ID видео состоит ровно из 11 символов [A-Za-z0-9_-].
     * Покрывает youtu.be/ID, /watch?v=ID, /embed/ID, /shorts/ID, /live/ID, /v/ID.
     */
    private static final Pattern YOUTUBE_ID = Pattern.compile(
            "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:[^#]*&)?v=|embed/|shorts/|live/|v/))([A-Za-z0-9_-]{11})"
    );

    @GetMapping("/preview")
    public ResponseEntity<Map<String, String>> getLinkPreview(
            @RequestParam String url,
            @RequestParam(required = false, defaultValue = "") String ua) {   // ← добавили параметр ua

        if (url == null || url.trim().isEmpty()) {
            System.out.println("Ошибка: URL не передан или пустой");
            return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
        }

        System.out.println("Запрос превью для URL: " + url);

        // YouTube обрабатываем ДО общего запроса: страницу видео YouTube не отдаёт
        // запросам с серверных IP (антибот), поэтому качать её бессмысленно.
        // Собираем превью из ID видео — миниатюру грузит уже браузер клиента.
        if (isYouTubeUrl(url)) {
            String videoId = extractYouTubeId(url);
            if (videoId != null) {
                System.out.println("YouTube-ссылка, собираем превью по ID видео: " + videoId);
                return ResponseEntity.ok(buildYouTubePreview(videoId, url));
            }
            System.out.println("YouTube-ссылка, но ID видео не распознан, идём общим путём: " + url);
        }

        // Выбираем User-Agent
        String userAgent;
        if (!ua.isEmpty()) {
            if ("facebookexternalhit".equals(ua)) {
                userAgent = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";
            } else {
                userAgent = ua; // если передадут другой
            }
        } else {
            // Запасной вариант — мобильный User-Agent (как в Android)
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
        }

        System.out.println("Используем User-Agent: " + userAgent);

        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(userAgent)                    // ← теперь используем нужный
                    .referrer("https://www.google.com/")
                    .timeout(15000)                          // увеличил до 15 сек
                    .followRedirects(true)
                    .get();

            // Title
            String title = Optional.ofNullable(doc.selectFirst("meta[property=og:title]"))
                    .map(el -> el.attr("content"))
                    .orElseGet(() -> doc.title().trim().isEmpty() ? "Untitled" : doc.title());

            // Description
            String description = Optional.ofNullable(doc.selectFirst("meta[property=og:description]"))
                    .map(el -> el.attr("content"))
                    .orElseGet(() -> Optional.ofNullable(doc.selectFirst("meta[name=description]"))
                            .map(el -> el.attr("content"))
                            .orElse(""));

            // Image
            String image = getBestImage(doc, url);

            Map<String, String> result = new HashMap<>();
            result.put("title", title);
            result.put("description", description);
            result.put("image", image);
            result.put("url", url);

            System.out.println("Успешно получено превью: title=" + title + ", image=" + (image.isEmpty() ? "нет" : image));

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            System.err.println("Не удалось получить превью для " + url + ": " + e.getMessage());
            // Возвращаем хоть что-то, чтобы фронт не падал
            return ResponseEntity.ok(Map.of(
                    "title", "Preview unavailable",
                    "description", "",
                    "image", "",
                    "url", url
            ));
        }
    }

    /**
     * Превью для YouTube без скачивания страницы видео.
     * Миниатюра строится из ID и грузится браузером напрямую с CDN,
     * название запрашивается через публичный oEmbed — он блокируется куда реже.
     * Если oEmbed недоступен, превью всё равно вернётся с картинкой.
     */
    private Map<String, String> buildYouTubePreview(String videoId, String url) {
        Map<String, String> result = new HashMap<>();
        result.put("url", url);
        result.put("image", "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");
        result.put("title", "YouTube");
        result.put("description", "");

        try {
            // Спрашиваем по канонической ссылке: с хвостом вроде «?si=…»,
            // который YouTube добавляет при «Поделиться», oEmbed отвечает
            // ошибкой, и заголовок остаётся просто «YouTube».
            String asked = "https://www.youtube.com/watch?v=" + videoId;
            String json = Jsoup.connect("https://www.youtube.com/oembed?format=json&url="
                            + URLEncoder.encode(asked, StandardCharsets.UTF_8))
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                    .ignoreContentType(true)
                    .timeout(7000)
                    .execute()
                    .body();

            JsonNode node = OBJECT_MAPPER.readTree(json);

            String title = node.path("title").asText("");
            if (!title.isEmpty()) result.put("title", title);

            String author = node.path("author_name").asText("");
            if (!author.isEmpty()) result.put("description", author);

            // Если oEmbed отдал свою миниатюру — она точнее нашей.
            String thumbnail = node.path("thumbnail_url").asText("");
            if (!thumbnail.isEmpty()) result.put("image", thumbnail);

            System.out.println("YouTube oEmbed: title=" + result.get("title"));

        } catch (Exception e) {
            System.err.println("YouTube oEmbed недоступен для " + url + ": " + e.getMessage()
                    + " — пробуем прочитать саму страницу видео");
        }

        // У ролика с запрещённым встраиванием oEmbed отвечает отказом. Тогда
        // читаем страницу: получится — возьмём настоящее название, не выйдет
        // (YouTube часто не отдаёт её серверным адресам) — останется миниатюра.
        if ("YouTube".equals(result.get("title"))) {
            try {
                Document page = Jsoup.connect("https://www.youtube.com/watch?v=" + videoId)
                        .userAgent("Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
                        .referrer("https://www.google.com/")
                        .timeout(10000)
                        .followRedirects(true)
                        .get();

                Element title = page.selectFirst("meta[property=og:title]");
                if (title != null && !title.attr("content").isEmpty()) {
                    result.put("title", title.attr("content"));
                    Element description = page.selectFirst("meta[property=og:description]");
                    if (description != null && !description.attr("content").isEmpty()) {
                        result.put("description", description.attr("content"));
                    }
                    System.out.println("YouTube: название взято со страницы видео: " + result.get("title"));
                }
            } catch (Exception pageError) {
                System.err.println("Страница видео тоже недоступна: " + pageError.getMessage());
            }
        }

        // Последняя надежда — официальный YouTube Data API. Он отвечает с
        // любого адреса, но требует ключ.
        if ("YouTube".equals(result.get("title")) && !youtubeApiKey.isBlank()) {
            try {
                String answer = Jsoup.connect("https://www.googleapis.com/youtube/v3/videos"
                                + "?part=snippet&id=" + videoId + "&key=" + youtubeApiKey)
                        .ignoreContentType(true)
                        .timeout(10000)
                        .execute()
                        .body();

                JsonNode snippet = OBJECT_MAPPER.readTree(answer).path("items").path(0).path("snippet");
                String title = snippet.path("title").asText("");
                if (!title.isEmpty()) {
                    result.put("title", title);
                    String channel = snippet.path("channelTitle").asText("");
                    String description = snippet.path("description").asText("");
                    result.put("description", !description.isEmpty() ? description : channel);
                    System.out.println("YouTube Data API: title=" + title);
                }
            } catch (Exception apiError) {
                System.err.println("YouTube Data API не ответил: " + apiError.getMessage());
            }
        }

        return result;
    }

    /**
     * Проверяет именно домен, а не вхождение подстроки,
     * чтобы чужая ссылка вида example.com/?u=youtu.be/xxx не считалась ютубовской.
     */
    private boolean isYouTubeUrl(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return host.equals("youtu.be")
                    || host.equals("youtube.com")
                    || host.endsWith(".youtube.com");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Получает лучшую доступную картинку
     */
    private String getBestImage(Document doc, String url) {
        // 1. Классический og:image
        String ogImage = Optional.ofNullable(doc.selectFirst("meta[property=og:image]"))
                .map(el -> el.attr("content"))
                .orElse("");

        if (!ogImage.isEmpty()) return makeAbsolute(ogImage, url);

        // 2. Специально для YouTube — самая надёжная картинка
        if (url.contains("youtube.com") || url.contains("youtu.be")) {
            String videoId = extractYouTubeId(url);
            if (videoId != null) {
                return "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
            }
        }

        // 3. Запасной: link rel="image_src"
        String imageSrc = doc.select("link[rel=image_src]").attr("href");
        if (!imageSrc.isEmpty()) return makeAbsolute(imageSrc, url);

        // 4. Запасной: первый большой img (ширина >= 300)
        Element bigImg = doc.select("img[width>=300]").first();
        if (bigImg != null) {
            String src = bigImg.absUrl("src");
            if (!src.isEmpty()) return src;
        }

        // 5. Если ничего не нашли — пусто
        return "";
    }

    /**
     * Делает относительный URL абсолютным
     */
    private String makeAbsolute(String src, String baseUrl) {
        try {
            if (src.startsWith("http")) return src;
            URL base = new URL(baseUrl);
            return new URL(base, src).toString();
        } catch (Exception e) {
            return src;
        }
    }

    /**
     * Извлекает ID видео из YouTube-ссылки...
     */
    private String extractYouTubeId(String url) {
        Matcher matcher = YOUTUBE_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }
}
