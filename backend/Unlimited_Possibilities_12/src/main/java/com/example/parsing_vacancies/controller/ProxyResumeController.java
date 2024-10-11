package com.example.parsing_vacancies.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/api/proxy")
public class ProxyResumeController {
    @Autowired
    private RestTemplate restTemplate;

    @PostMapping("/send-resume")
    public ResponseEntity<String> sendResume(@RequestBody ProxyRequest proxyRequest) {
        try {
            // Получение параметров из запроса
            String token = proxyRequest.getToken();
            long vacancyId = proxyRequest.getVacancyIdRabotaUa();
            String email = proxyRequest.getEmail();
            String targetUrl = proxyRequest.getTargetUrl();
            String resumeContent = proxyRequest.getResumeContent();

            // Формирование JSON-строки
            /*String jsonBody = String.format("{\"addAlert\":true,\"attachId\":22403002,\"firstName\":\"И.Ж.\",\"lastName\":\"И.Ж.\",\"email\":\"%s\",\"letter\":\"\",\"vacancyId\":%d,\"resumeContent\":\"%s\"}",
                    email, vacancyId, resumeContent);*/
            String jsonBody = String.format("{\"addAlert\":true,\"attachId\":22403002,\"firstName\":\"И.Ж.\",\"lastName\":\"И.Ж.\",\"email\":\"%s\",\"letter\":\"\",\"vacancyId\":%d}",
                    email, vacancyId);

            // Настройка заголовков
            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", "Bearer " + token);
            //headers.add("Content-Type", "application/*+json");
            headers.add("Content-Type", "application/json");
            headers.add("Accept", "text/plain");
            headers.add("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7,uk;q=0.6");
            headers.add("sec-fetch-dest", "empty");
            headers.add("sec-fetch-mode", "cors");
            headers.add("sec-fetch-site", "same-site");

            // Создание запроса
            HttpEntity<String> requestEntity = new HttpEntity<>(jsonBody, headers);

            // Отправка POST-запроса через прокси
            ResponseEntity<String> response = restTemplate.postForEntity(targetUrl, requestEntity, String.class);

            return response;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // Логирование полной информации об ошибке
            System.out.println("Ошибка при обращении к API: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            // Обработка других исключений
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка при отправке резюме1: " + e.getMessage());
        }
    }

    // Вспомогательный класс для передачи данных в прокси
    private static class ProxyRequest {
        private String token;
        private String filePath;
        private Long vacancyIdRabotaUa;
        private String email;
        private String resumeContent;
        private String targetUrl; // Добавлено поле apiUrl

        // Конструктор и геттеры
        public ProxyRequest(String token, String filePath, Long vacancyIdRabotaUa, String email, String resumeContent, String targetUrl) {
            this.token = token;
            this.filePath = filePath;
            this.vacancyIdRabotaUa = vacancyIdRabotaUa;
            this.email = email;
            this.resumeContent = resumeContent;
            this.targetUrl = targetUrl; // Инициализация
        }

        public String getToken() {
            return token;
        }

        public String getFilePath() {
            return filePath;
        }

        public Long getVacancyIdRabotaUa() {
            return vacancyIdRabotaUa;
        }

        public String getEmail() {
            return email;
        }

        public String getResumeContent() {
            return resumeContent;
        }

        public String getTargetUrl() {
            return targetUrl; // Геттер для apiUrl
        }
    }
}
