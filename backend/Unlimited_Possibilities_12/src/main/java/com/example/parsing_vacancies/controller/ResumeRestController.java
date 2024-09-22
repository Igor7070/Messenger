package com.example.parsing_vacancies.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.net.URI;

@RestController
@RequestMapping("/convenient_website777/readyResume")
public class ResumeRestController {

    @Autowired
    private RestTemplate customRestTemplate;

    @PostMapping("/upload")
    public ResponseEntity<String> uploadResume(@RequestParam("vacancyId") Long vacancyId,
                                               @RequestParam("resumeFile") String resumeFile) {
        try {
            // Путь к файлу резюме в папке static/resume
            System.out.println("Получен POST-запрос на загрузку резюме");
            String filePath = "src/main/resources/static/resumes/" + resumeFile; // Укажите имя файла
            //Path filePath = Paths.get("src/main/resources/static/resumes").resolve(resumeFile).normalize();
            //System.out.println(filePath);
            File file = new File(filePath.toString());
            FileSystemResource resource = new FileSystemResource(file);

            // Подготовка запроса
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.add("User-Agent", "ResumeSubmitter/1.0 (Windows 10; Java 11)");

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("resume", resource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            String targetUrl = "https://www.work.ua/ru/jobseeker/my/resumes/send/?id=5831808"; // Укажите конечный URL

            // Отправка POST-запроса
            ResponseEntity<String> response = customRestTemplate.postForEntity(targetUrl, requestEntity, String.class);
            // Проверка ответа
            if (response.getStatusCode() == HttpStatus.OK) {
                System.out.println("Резюме успешно отправлено");
            } else if (response.getStatusCode() == HttpStatus.FOUND) {
                String location = response.getHeaders().getLocation().toString();
                System.out.println("Перенаправление на: " + location);

                // Выполнение нового запроса по новому адресу
                HttpEntity<Void> redirectRequestEntity = new HttpEntity<>(headers);
                ResponseEntity<String> redirectedResponse = customRestTemplate.exchange(location, HttpMethod.GET, redirectRequestEntity, String.class);
                // Обработка ответа от перенаправленного URL
                System.out.println("Ответ от перенаправленного URL: " + redirectedResponse.getBody());
            } else {
                System.out.println("Ошибка отправки резюме: " + response.getStatusCode() + " - " + response.getBody());
            }

            // Перенаправление на страницу с успешным сообщением
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/convenient_website777/sent?vacancyId=" + vacancyId))
                    .build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Ошибка: " + e.getMessage());
        }
    }
}
