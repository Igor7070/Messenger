package com.example.unl_pos12.controller.messenger;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class PreviewController {

    @GetMapping("/preview")
    public ResponseEntity<Map<String, String>> getLinkPreview(@RequestParam String url) {
        if (url == null || url.trim().isEmpty()) {
            System.out.println("Ошибка: URL не передан или пустой");
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "URL is required"));
        }

        System.out.println("Запрос превью для URL: " + url);

        try {
            // Улучшенные заголовки + больший таймаут
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .referrer("https://www.google.com/")
                    .timeout(10000)  // 10 секунд — YouTube часто требует больше времени
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

            // Image — самый важный блок
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
            // e.printStackTrace();  // Раскомментировать для полной трассировки в консоли

            // Фронт не должен падать — возвращаем пустой объект
            return ResponseEntity.ok(Map.of());
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
                return "https://img.youtube.com/vi/" + videoId + "/hqdefault.jpg";
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
     * Извлекает ID видео из YouTube-ссылки
     */
    private String extractYouTubeId(String url) {
        Pattern pattern = Pattern.compile(
                "(?<=watch\\?v=|/videos/|embed\\/|youtu.be\\/|\\/v\\/|watch\\?v%3D|watch\\?feature=player_embedded&v=|%2Fvideos%2F|embed%\u200C\u200B2F|youtu.be%2F|%2Fv%2F)[^#\\&\\?\\n]*"
        );
        Matcher matcher = pattern.matcher(url);
        return matcher.find() ? matcher.group() : null;
    }
}
