package com.example.unl_pos12.controller.messenger;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(5000)
                    .get();

            String title = Optional.ofNullable(doc.selectFirst("meta[property=og:title]"))
                    .map(el -> el.attr("content"))
                    .orElseGet(() -> doc.title().trim().isEmpty() ? "Без названия" : doc.title());

            String description = Optional.ofNullable(doc.selectFirst("meta[property=og:description]"))
                    .map(el -> el.attr("content"))
                    .orElseGet(() -> Optional.ofNullable(doc.selectFirst("meta[name=description]"))
                            .map(el -> el.attr("content"))
                            .orElse(""));

            String image = Optional.ofNullable(doc.selectFirst("meta[property=og:image]"))
                    .map(el -> el.attr("content"))
                    .orElse("");

            Map<String, String> result = new HashMap<>();
            result.put("title", title);
            result.put("description", description);
            result.put("image", image);
            result.put("url", url);

            System.out.println("Успешно получено превью: title=" + title + ", image=" + image);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            System.err.println("Не удалось получить превью для " + url + ": " + e.getMessage());
            // Можно вывести стек-трейс полностью, если нужно отладить:
            // e.printStackTrace();

            // Возвращаем пустой объект, фронт не упадёт
            return ResponseEntity.ok(Map.of());
        }
    }
}
