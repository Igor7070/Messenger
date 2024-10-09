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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
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
        try {
            // Получение текущей аутентификации
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String accessToken = null;

            if (authentication instanceof OAuth2AuthenticationToken) {
                OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
                OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                        oauthToken.getAuthorizedClientRegistrationId(), oauthToken.getName());
                accessToken = client.getAccessToken().getTokenValue();
            }

            // Проверка, был ли токен получен
            if (accessToken == null) {
                session.setAttribute("message", "Ошибка: не удалось получить токен доступа. Вам необходимо авторизироваться.");
                // Перенаправление на страницу с успешным сообщением
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                        .build();
            }
            System.out.println("accessToken: " + accessToken);

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
            String targetUrl = pageForSendingResume(vacancy); // Конечный URL
            System.out.println("targetUrl: " + targetUrl);
            if (vacancy.getSiteName().contains("robota.ua")) {
                System.out.println("Vacancy from robota.ua: YES");
                headers.add("Authorization", "Bearer " + accessToken); // Добавление токена
                headers.add("Accept", "application/json, text/plain, */*");
                headers.add("Referer", "https://robota.ua/");
                headers.add("Origin", "https://robota.ua/");
            }

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

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
                session.setAttribute("message", "Ошибка отправки резюме: " + response.getStatusCode() + " - " + response.getBody());
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                        .build();
            } else {
                System.out.println("Ошибка отправки резюме: " + response.getStatusCode() + " - " + response.getBody());
                session.setAttribute("message", "Ошибка отправки резюме: " + response.getStatusCode() + " - " + response.getBody());
                return ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                        .build();
            }

            // Перенаправление на страницу с успешным сообщением
            session.setAttribute("message", "Ваше резюме успешно отправлено!");
            session.setAttribute("targetUrl", targetUrl); // Сохраняем targetUrl в сессии
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                    .build();
        } catch (Exception e) {
            //return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Ошибка: " + e.getMessage());
            session.setAttribute("message", "Ошибка отправки резюме: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/convenient_job_search/readyResume/sent?vacancyId=" + vacancyId))
                    .build();
        }
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
}
