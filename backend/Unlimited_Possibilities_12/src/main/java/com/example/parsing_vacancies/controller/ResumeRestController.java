package com.example.parsing_vacancies.controller;

import com.example.parsing_vacancies.model.Vacancy;
import com.example.parsing_vacancies.repo.VacancyRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/convenient_job_search/readyResume")
public class ResumeRestController {

    @Autowired
    private RestTemplate customRestTemplate;
    @Autowired
    private VacancyRepository vacancyRepository;
    @Autowired
    private OAuth2AuthorizedClientService authorizedClientService;

    @PostMapping("/upload")
    public ResponseEntity<String> uploadResume(@RequestParam("vacancyId") Long vacancyId,
                                               @RequestParam("resumeFile") String resumeFile,
                                               HttpSession session) {
        String submitPageUrl = "";
        try {
            // Получение текущей аутентификации
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            // Получение токена, маил, firstName и lastName
            String accessToken = extractGoogleAccessToken(authentication);
            String email = extractEmail(authentication);
            String firstName = extractFirstName(authentication);
            String lastName = extractLastName(authentication);

            // Проверка, был ли токен получен
            if (accessToken == null) {
                session.setAttribute("message", "Ошибка: не удалось получить токен доступа. Вам необходимо авторизироваться.");
                // Перенаправление на страницу с успешным сообщением
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                        .build();
            }
            System.out.println("accessToken: " + accessToken);
            System.out.println("eMail: " + email);
            System.out.println("firstName: " + firstName);
            System.out.println("lastName: " + lastName);

            // Путь к файлу резюме в папке static/resume
            System.out.println("Получен POST-запрос на загрузку резюме");
            String filePath = "src/main/resources/static/resumes/" + resumeFile; // Укажите имя файла
            //Path filePath = Paths.get("src/main/resources/static/resumes").resolve(resumeFile).normalize();
            //System.out.println(filePath);
            File file = new File(filePath);
            FileSystemResource resource = new FileSystemResource(file);

            // Подготовка запроса
            HttpHeaders headers = new HttpHeaders();
            //headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            //headers.add("User-Agent", "ResumeSubmitter/1.0 (Windows 10; Java 11)");
            headers.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36");

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("resume", resource);

            Optional<Vacancy> vacancyOpt = vacancyRepository.findById(Math.toIntExact(vacancyId));
            ArrayList<Vacancy> res = new ArrayList<>();
            vacancyOpt.ifPresent(res::add);
            if (res.isEmpty()) {
                // Сохранение сообщения об ошибке в сессии
                session.setAttribute("message", "Удалена история поиска вакансий и данная вакансия недоступна теперь. Попробуйте снова.");
                // Перенаправление на страницу с успешным сообщением
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                        .build();
            }
            Vacancy vacancy = res.get(0);
            System.out.println(vacancy.getId());
            System.out.println(vacancy.getTitle());
            System.out.println(vacancy.getCompanyName());
            System.out.println(vacancy.getCity());
            System.out.println(vacancy.getSiteName());
            System.out.println(vacancy.getUrl());
            submitPageUrl = pageForSendingResume(vacancy); // URL страницы где происходит отправка резюме
            System.out.println("submitPageUrl: " + submitPageUrl);

            if (vacancy.getSiteName().contains("robota.ua")) {
                byte[] fileBytes = Files.readAllBytes(file.toPath());
                String encodedFile = Base64.getEncoder().encodeToString(fileBytes);

                String targetProxyLoadUrl = "https://unlimitedpossibilities12.org/api/proxy/upload-resume";
                String targetProxySendUrl = "https://unlimitedpossibilities12.org/api/proxy/send-resume";
                String vacancyIdRabotaUa = extractIdVacancy(vacancy.getUrl());
                long vacancyIdRabotaUaLong = Long.parseLong(vacancyIdRabotaUa);
                String targetLoadUrl = "https://apply-api.robota.ua/attach-application";
                String targetSendUrl = "https://apply-api.robota.ua/attach-repeated-application";

                // Создание объекта запроса для прокси
                ProxyRequest proxyRequest = new ProxyRequest(accessToken, filePath,
                        vacancyIdRabotaUaLong, email, firstName, lastName, encodedFile,
                        targetLoadUrl, targetSendUrl);

                // Отправка POST-запросов через прокси
                ResponseEntity<String> responseLoad = customRestTemplate.postForEntity(targetProxyLoadUrl, proxyRequest, String.class);
                ResponseEntity<String> responseSend = customRestTemplate.postForEntity(targetProxySendUrl, proxyRequest, String.class);
                System.out.println("ResponceSend: " + responseSend.getBody());

                // Проверка ответа
                if ((responseLoad.getStatusCode() == HttpStatus.OK) && (responseSend.getStatusCode() == HttpStatus.OK)) {
                    System.out.println("Резюме успешно отправлено");
                } else if (responseSend.getStatusCode() == HttpStatus.FOUND) {
                    String locationSend = responseSend.getHeaders().getLocation().toString();
                    System.out.println("Перенаправление на: " + locationSend);

                    // Выполнение нового запроса по новому адресу
                    HttpEntity<Void> redirectRequestEntity = new HttpEntity<>(headers);
                    ResponseEntity<String> redirectedResponse = customRestTemplate.exchange(locationSend, HttpMethod.GET, redirectRequestEntity, String.class);
                    // Обработка ответа от перенаправленного URL
                    System.out.println("Ответ от перенаправленного URL: " + redirectedResponse.getBody());
                    session.setAttribute("message", "Ошибка отправки резюме: " + responseSend.getStatusCode() + " - " + responseSend.getBody());
                    session.setAttribute("submitPageUrl", submitPageUrl); // Сохраняем targetUrl в сессии
                    return ResponseEntity.status(HttpStatus.FOUND)
                            .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                            .build();
                } else if (responseLoad.getStatusCode() != HttpStatus.OK) {
                    String message = "";
                    if (responseSend.getStatusCode() != HttpStatus.OK) {
                        message = "Ошибка загрузки резюме: " + responseSend.getStatusCode() + " - " + responseSend.getBody() + "\n";
                        System.out.println(message);
                    }
                    message = message + "Ошибка отправки резюме: " + responseLoad.getStatusCode() + " - " + responseLoad.getBody();
                    System.out.println("Ошибка отправки резюме: " + responseLoad.getStatusCode() + " - " + responseLoad.getBody());
                    session.setAttribute("message", message);
                    session.setAttribute("submitPageUrl", submitPageUrl);
                    return ResponseEntity.status(HttpStatus.FOUND)
                            .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                            .build();
                }
                // Перенаправление на страницу с успешным сообщением
                session.setAttribute("message", "Ваше резюме успешно отправлено!");
                session.setAttribute("submitPageUrl", submitPageUrl); // Сохраняем targetUrl в сессии
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                        .build();
            }

            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            // Отправка POST-запроса
            ResponseEntity<String> response = customRestTemplate.postForEntity(submitPageUrl, requestEntity, String.class);
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
                session.setAttribute("message", "Ошибка отправки резюме: " + response.getStatusCode() + " - " + response.getBody());
                session.setAttribute("submitPageUrl", submitPageUrl); // Сохраняем targetUrl в сессии
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                        .build();
            } else {
                System.out.println("Ошибка отправки резюме: " + response.getStatusCode() + " - " + response.getBody());
                session.setAttribute("message", "Ошибка отправки резюме: " + response.getStatusCode() + " - " + response.getBody());
                session.setAttribute("submitPageUrl", submitPageUrl); // Сохраняем targetUrl в сессии
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                        .build();
            }
            // Перенаправление на страницу с успешным сообщением
            session.setAttribute("message", "Ваше резюме успешно отправлено!");
            session.setAttribute("submitPageUrl", submitPageUrl); // Сохраняем targetUrl в сессии
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                    .build();
        } catch (Exception e) {
            //return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Ошибка: " + e.getMessage());
            session.setAttribute("message", "Ошибка отправки резюме: " + e.getMessage());
            session.setAttribute("submitPageUrl", submitPageUrl); // Сохраняем targetUrl в сессии
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                    .build();
        }
    }

    private String extractGoogleAccessToken(Authentication authentication) {
        String accessToken = null;
        if (authentication instanceof OAuth2AuthenticationToken) {
            OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
            OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                    oauthToken.getAuthorizedClientRegistrationId(), oauthToken.getName());
            accessToken = client.getAccessToken().getTokenValue();
        }
        return accessToken;
    }

    public String extractEmail(Authentication authentication) {
        String email = null;
        if (authentication instanceof OAuth2AuthenticationToken) {
            OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
            OAuth2User user = oauthToken.getPrincipal();
            email = user.getAttribute("email");
        }
        return email;
    }

    public String extractFirstName(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken) {
            OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
            OAuth2User user = oauthToken.getPrincipal();
            return user.getAttribute("given_name");
        }
        return null;
    }

    public String extractLastName(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken) {
            OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
            OAuth2User user = oauthToken.getPrincipal();
            return user.getAttribute("family_name");
        }
        return null;
    }

    private String pageForSendingResume(Vacancy vacancy) {
        String targetUrl = null;
        String targetUrlWorkUaFormat = "https://www.work.ua/ru/jobseeker/my/resumes/send/?id=%s";
        //String targetUrlRabotaUaFormat = "https://robota.ua/ru/company%s/vacancy%s/apply";
        String targetUrlRabotaUaFormat = "https://robota.ua/ru/company%s/vacancy%s/apply?newApply=true";
        String site = vacancy.getSiteName();
        String url = vacancy.getUrl();

        if (site.contains("work.ua")) {
            String regex = "jobs/(\\d+)";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                targetUrl = String.format(targetUrlWorkUaFormat, matcher.group(1)); // Возвращаем первую группу (число)
            }
        } else if (site.contains("robota.ua")) {
            String regex = "company(\\d+)/vacancy(\\d+)";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(url);
            if (matcher.find()) {
                targetUrl = String.format(targetUrlRabotaUaFormat, matcher.group(1), matcher.group(2));
            }
        }

        return targetUrl;
    }

    private String extractIdVacancy(String targetUrl) {
        String regex = "vacancy(\\d+)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(targetUrl);
        if (matcher.find()) {
           return matcher.group(1);
        }
        return "";
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

        // Геттеры
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
