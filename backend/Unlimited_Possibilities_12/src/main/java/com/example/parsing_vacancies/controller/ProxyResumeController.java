package com.example.parsing_vacancies.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/proxy")
public class ProxyResumeController {
    @Autowired
    private RestTemplate restTemplate;

    // Метод для загрузки резюме
    @PostMapping("/upload-resume")
    public ResponseEntity<String> uploadResume(@RequestBody ProxyRequest proxyRequest) {
        ResponseEntity<String> response = null;
        String targetLoadUrl = "";
        try {
            // Получение параметров из запроса
            String token = proxyRequest.getToken();
            String filePath = proxyRequest.getFilePath();
            long vacancyIdRabotaUa = proxyRequest.getVacancyIdRabotaUa();
            String email = proxyRequest.getEmail();
            String firstName = proxyRequest.getFirstName();
            String lastName = proxyRequest.getLastName();
            targetLoadUrl = proxyRequest.targetLoadUrl;

            System.out.println("targetLoadUrl: " + targetLoadUrl);

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
            response = restTemplate.postForEntity(targetLoadUrl, requestEntity, String.class);

            System.out.println("ResponseLoad: " + response.getBody());
            // Предположим, что ответ содержит идентификатор:
            String requestId = extractRequestId(response.getBody()); // Метод для извлечения идентификатора
            // Запускаем polling для проверки статуса
            startPolling(requestId, targetLoadUrl);

            return response;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // Логирование полной информации об ошибке
            System.out.println("Ошибка при обращении к API: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            //return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());

            System.out.println("ResponseLoad: " + response.getBody());
            // Предположим, что ответ содержит идентификатор:
            String requestId = extractRequestId(response.getBody()); // Метод для извлечения идентификатора
            // Запускаем polling для проверки статуса
            startPolling(requestId, targetLoadUrl);

            return response;
        } catch (Exception e) {
            // Обработка других исключений
            e.printStackTrace();
            //return ResponseEntity.status(500).body("Ошибка при загрузке резюме1.1: " + e.getMessage());

            System.out.println("ResponseLoad: " + response.getBody());
            // Предположим, что ответ содержит идентификатор:
            String requestId = extractRequestId(response.getBody()); // Метод для извлечения идентификатора
            // Запускаем polling для проверки статуса
            startPolling(requestId, targetLoadUrl);

            return response;
        }
    }

    @PostMapping("/send-resume")
    public ResponseEntity<String> sendResume(@RequestBody ProxyRequest proxyRequest) {
        try {
            // Получение параметров из запроса
            String token = proxyRequest.getToken();
            long vacancyIdRabotaUa = proxyRequest.getVacancyIdRabotaUa();
            String email = proxyRequest.getEmail();
            String firstName = proxyRequest.firstName;
            String lastName = proxyRequest.lastName;
            String targetSendUrl = proxyRequest.getTargetSendUrl();
            String resumeContent = proxyRequest.getResumeContent();

            System.out.println("targetSendUrl: " + targetSendUrl);

            System.out.println("token: " + token);
            System.out.println("vacancyIdRabotaUa: " + vacancyIdRabotaUa);
            System.out.println("email: " + email);
            System.out.println("firstName: " + firstName);
            System.out.println("lastName: " + lastName);
            System.out.println("targetUrl: " + targetSendUrl);

            // Формирование JSON-строки
            /*String jsonBody = String.format("{\"addAlert\":true,\"attachId\":22403002,\"firstName\":\"И.Ж.\",\"lastName\":\"И.Ж.\",\"email\":\"%s\",\"letter\":\"\",\"vacancyId\":%d,\"resumeContent\":\"%s\"}",
                    email, vacancyId, resumeContent);*/
            String jsonBody = String.format("{\"addAlert\":true,\"attachId\":22403002,\"firstName\":\"%s\",\"lastName\":\"%s\",\"email\":\"%s\",\"letter\":\"\",\"vacancyId\":%d}",
                    firstName, lastName, email, vacancyIdRabotaUa);

            // Настройка заголовков
            HttpHeaders headers = new HttpHeaders();
            headers.add("Authorization", "Bearer " + token);
            //headers.add("Content-Type", "application/*+json");
            headers.add("Content-Type", "application/*+json");
            headers.add("Accept", "text/plain");
            headers.add("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7,uk;q=0.6");
            headers.add("sec-fetch-dest", "empty");
            headers.add("sec-fetch-mode", "cors");
            headers.add("sec-fetch-site", "same-site");

            // Создание запроса
            HttpEntity<String> requestEntity = new HttpEntity<>(jsonBody, headers);

            // Отправка POST-запроса через прокси
            ResponseEntity<String> response = restTemplate.postForEntity(targetSendUrl, requestEntity, String.class);
            System.out.println("ResponseSend: " + response.getBody());

            // Предположим, что ответ содержит идентификатор:
            String requestId = extractRequestId(response.getBody()); // Метод для извлечения идентификатора
            // Запускаем polling для проверки статуса
            startPolling(requestId, targetSendUrl);

            return response;
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            // Логирование полной информации об ошибке
            System.out.println("Ошибка при обращении к API: " + e.getStatusCode() + " - " + e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            // Обработка других исключений
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка при отправке резюме1.2: " + e.getMessage());
        }
    }

    // Метод для извлечения идентификатора из ответа
    private String extractRequestId(String responseBody) {
        // Пример для JSON-ответа, который содержит поле requestId
        // Используйте библиотеку для парсинга JSON, например, Jackson или Gson
        String requestId = "";
        JsonNode jsonNode = null;
        try {
            jsonNode = new ObjectMapper().readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        requestId = jsonNode.get("id").asText();
        System.out.println("requestId: " + requestId);

        return requestId; // Измените путь в зависимости от структуры ответа
    }

    // Метод для опроса статуса
    private void startPolling(String requestId, String targetUrl) {
        new Thread(() -> {
            try {
                while (true) {
                    ResponseEntity<String> statusResponse = checkStatus(requestId, targetUrl);

                    // Обработка статуса
                    if (statusResponse.getStatusCode() == HttpStatus.OK) {
                        System.out.println("Статус: " + statusResponse.getBody());
                        // Если статус успешный, можно остановить опрос
                        break;
                    }

                    // Задержка перед следующим опросом
                    Thread.sleep(5000); // 5 секунд
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    // Метод для проверки статуса
    private ResponseEntity<String> checkStatus(String requestId, String targetUrl) {
        String statusUrl = targetUrl; // URL для проверки статуса
        return restTemplate.getForEntity(statusUrl, String.class);
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
        private String targetLoadUrl;
        private String targetSendUrl;

        // Конструктор и геттеры
        public ProxyRequest(String token, String filePath, Long vacancyIdRabotaUa, String email,
                            String firstName, String lastName, String resumeContent,
                            String targetLoadUrl, String targetSendUrl) {
            this.token = token;
            this.filePath = filePath;
            this.vacancyIdRabotaUa = vacancyIdRabotaUa;
            this.email = email;
            this.firstName = firstName;
            this.lastName = lastName;
            this.resumeContent = resumeContent;
            this.targetLoadUrl = targetLoadUrl;
            this.targetSendUrl = targetSendUrl;
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

        public String getTargetLoadUrl() {
            return targetLoadUrl;
        }

        public String getTargetSendUrl() {
            return targetSendUrl;
        }
    }
}
