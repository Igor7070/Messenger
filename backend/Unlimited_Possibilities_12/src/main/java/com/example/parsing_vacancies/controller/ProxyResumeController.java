package com.example.parsing_vacancies.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/proxy")
public class ProxyResumeController {
    @Autowired
    private RestTemplate restTemplate;

    // Метод для загрузки резюме work.ua
    @PostMapping("/upload-send-resume-work-ua")
    public ResponseEntity<String> uploadResumeWorkUa(@RequestBody ProxyRequest proxyRequest) {
        ResponseEntity<String> response = null;
        String targetLoadSendUrl = "";
        try {
            // Получение параметров из запроса
            String token = proxyRequest.getToken();
            String filePath = proxyRequest.getFilePath();
            long vacancyIdRabotaUa = proxyRequest.getVacancyIdRabotaUa();
            String email = proxyRequest.getEmail();
            String firstName = proxyRequest.getFirstName();
            String lastName = proxyRequest.getLastName();
            targetLoadSendUrl = proxyRequest.getTargetLoadSendUrl();
            String submitPageUrl = proxyRequest.getSubmitPageUrl();

            System.out.println("targetLoadSendUrl: " + targetLoadSendUrl);

            // Чтение файла резюме
            File resumeFile = new File(filePath);
            if (!resumeFile.exists()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Файл резюме не найден по пути: " + filePath);
            }

            // Настройка заголовков
            HttpHeaders headers = new HttpHeaders();
            headers.add("Accept", "application/json, text/javascript, */*; q=0.01");
            headers.add("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7,uk;q=0.6");
            headers.add("Content-Type", "multipart/form-data; boundary=----WebKitFormBoundaryBHMUC5o8EfkniAtw");
            headers.add("Priority", "u=1, i");
            headers.add("Sec-CH-UA", "\"Google Chrome\";v=\"129\", \"Not=A?Brand\";v=\"8\", \"Chromium\";v=\"129\"");
            headers.add("Sec-CH-UA-Mobile", "?0");
            headers.add("Sec-CH-UA-Platform", "\"Windows\"");
            headers.add("Sec-Fetch-Dest", "empty");
            headers.add("Sec-Fetch-Mode", "cors");
            headers.add("Sec-Fetch-Site", "same-origin");
            headers.add("X-Requested-With", "XMLHttpRequest");
            headers.add("Referer", submitPageUrl);

            File file = new File(filePath);
            String fileName = file.getName();

            // Формирование тела запроса
            String boundary = "----WebKitFormBoundaryBHMUC5o8EfkniAtw";
            StringBuilder bodyBuilder = new StringBuilder();
            bodyBuilder.append(boundary).append("\r\n");
            bodyBuilder.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fileName).append("\"\r\n");
            bodyBuilder.append("Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n");

            // Чтение файла
            byte[] fileContent = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                fis.read(fileContent);
            }

            bodyBuilder.append(new String(fileContent, StandardCharsets.UTF_8)).append("\r\n");
            bodyBuilder.append(boundary).append("--\r\n");

            // Создание HttpEntity
            HttpEntity<String> requestEntity = new HttpEntity<>(bodyBuilder.toString(), headers);

            // Выполнение POST-запроса
            response = restTemplate.exchange(targetLoadSendUrl, HttpMethod.POST, requestEntity, String.class);

            // Формирование тела запроса
            /*MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(resumeFile));
            // Создание запроса
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            // Отправка POST-запроса через прокси
            response = restTemplate.postForEntity(targetLoadSendUrl, requestEntity, String.class);
            System.out.println("ResponseLoadSend: " + response.getBody());*/

            //return ResponseEntity.ok("Резюме загружено и отправлено.");
            return response;
            //return response;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // Логирование полной информации об ошибке
            System.out.println("Ошибка при обращении к API: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            // Обработка других исключений
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка при загрузке резюме1.1: " + e.getMessage());
        }
    }

    // Метод для загрузки резюме rabota.ua
    @PostMapping("/upload-send-resume-rabota-ua")
    public ResponseEntity<String> uploadResumeRabotaUa(@RequestBody ProxyRequest proxyRequest) {
        ResponseEntity<String> response = null;
        String targetLoadSendUrl = "";
        try {
            // Получение параметров из запроса
            String token = proxyRequest.getToken();
            String filePath = proxyRequest.getFilePath();
            long vacancyIdRabotaUa = proxyRequest.getVacancyIdRabotaUa();
            String email = proxyRequest.getEmail();
            String firstName = proxyRequest.getFirstName();
            String lastName = proxyRequest.getLastName();
            targetLoadSendUrl = proxyRequest.getTargetLoadSendUrl();

            System.out.println("targetLoadSendUrl: " + targetLoadSendUrl);

            // Чтение файла резюме
            File resumeFile = new File(filePath);
            if (!resumeFile.exists()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Файл резюме не найден по пути: " + filePath);
            }

            // Настройка заголовков
            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", "Bearer " + token);
            headers.add("Accept", "text/plain");
            headers.add("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7,uk;q=0.6");
            headers.add("Content-Type", "multipart/form-data");

            // Формирование тела запроса
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(resumeFile));
            body.add("firstName", firstName);
            body.add("lastName", lastName);
            body.add("email", email);
            body.add("vacancyId", vacancyIdRabotaUa);
            body.add("addAlert", true);
            body.add("letter", "");

            // Создание запроса
            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            // Отправка POST-запроса через прокси
            response = restTemplate.postForEntity(targetLoadSendUrl, requestEntity, String.class);
            System.out.println("ResponseLoadSend: " + response.getBody());

            return ResponseEntity.ok("Резюме загружено и отправлено.");
            //return response;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // Логирование полной информации об ошибке
            System.out.println("Ошибка при обращении к API: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            // Обработка других исключений
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка при загрузке резюме1.1: " + e.getMessage());
        }
    }

    // Метод для проверки статуса
    private ResponseEntity<String> checkStatus(String token) {
        System.out.println("Working checkStatus...");
        String statusUrl = "https://api.robota.ua/resume"; // URL для проверки статуса

        // Настройка заголовков
        HttpHeaders headers = new HttpHeaders();
        headers.add("Accept", "application/json, text/plain, */*");
        headers.add("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7,uk;q=0.6");
        headers.add("Authorization", "Bearer " + token);
        headers.add("Priority", "u=1, i");
        headers.add("Sec-CH-UA", "\"Google Chrome\";v=\"129\", \"Not=A?Brand\";v=\"8\", \"Chromium\";v=\"129\"");
        headers.add("Sec-CH-UA-Mobile", "?0");
        headers.add("Sec-CH-UA-Platform", "\"Windows\"");
        headers.add("Sec-Fetch-Dest", "empty");
        headers.add("Sec-Fetch-Mode", "cors");
        headers.add("Sec-Fetch-Site", "same-site");

        // Создание запроса
        HttpEntity<String> requestEntity = new HttpEntity<>(headers);
        // Выполнение GET-запроса
        return restTemplate.exchange(statusUrl, HttpMethod.GET, requestEntity, String.class);
        //return restTemplate.getForEntity(statusUrl, String.class);
    }


    // Вспомогательный класс для передачи данных в прокси
    private static class ProxyRequest {
        private String token;
        private String filePath;
        private Long vacancyIdRabotaUa;
        private String email;
        private String firstName;
        private String lastName;
        private String resumeContent;
        private String targetLoadSendUrl;
        private String submitPageUrl;

        // Конструктор и геттеры
        public ProxyRequest(String token, String filePath, Long vacancyIdRabotaUa, String email,
                            String firstName, String lastName, String resumeContent,
                            String targetLoadSendUrl, String submitPageUrl) {
            this.token = token;
            this.filePath = filePath;
            this.vacancyIdRabotaUa = vacancyIdRabotaUa;
            this.email = email;
            this.firstName = firstName;
            this.lastName = lastName;
            this.resumeContent = resumeContent;
            this.targetLoadSendUrl = targetLoadSendUrl;
            this.submitPageUrl = submitPageUrl;
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

        public String getFirstName() {
            return firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public String getResumeContent() {
            return resumeContent;
        }

        public String getTargetLoadSendUrl() {
            return targetLoadSendUrl;
        }

        public String getSubmitPageUrl() {
            return submitPageUrl;
        }
    }
}
