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
                    .orElseGet(() -> doc.title().trim().isEmpty() ? "Без названия" : doc.title());

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
                    "title", "Не удалось загрузить превью",
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
            String json = Jsoup.connect("https://www.youtube.com/oembed?format=json&url="
                            + URLEncoder.encode(url, StandardCharsets.UTF_8))
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
                    + " — отдаём превью только с миниатюрой");
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
