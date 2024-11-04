package com.example.parsing_vacancies.controller;

import com.example.parsing_vacancies.controller.telegram.TelegramBotController;
import com.example.parsing_vacancies.model.telegram.UserData;
import com.example.parsing_vacancies.service.AuthenticationDebugService;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

@Controller
public class AuthController {
    @Autowired
    private AuthenticationDebugService authDebugService;
    @Autowired
    private OAuth2AuthorizedClientService authorizedClientService; // Сервис для получения токена
    @Autowired
    private TelegramBotController telegramBotController;
    private final HttpTransport transport = new NetHttpTransport();
    private final JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.client-secret}")
    private String clientSecret;

    @GetMapping("/login")
    public String login(HttpSession session, @RequestParam(required = false) String chatId) {
        System.out.println("Method login working...");
        System.out.println("chatId: " + chatId);
        session.setAttribute("chatId", chatId);
        // Перенаправление на Google для аутентификации
        System.out.println("Autorization");
        return "redirect:/oauth2/authorization/google";
    }

    @GetMapping("/autorization_success")
    public String oauth2Callback(OAuth2AuthenticationToken authentication, HttpSession session,
                                 HttpServletResponse response) {
        System.out.println("Method oauth2Callback working...");
        // Проверяем текущую аутентификацию
        authDebugService.logCurrentAuthentication();

        if (authentication == null) {
            System.out.println("Authentication is null");
            return "redirect:/login"; // Перенаправление на страницу логина
        }

        System.out.println("Success authorization");
        System.out.println("Authentication details: " + authentication);
        String email = authentication.getPrincipal().getAttribute("email");
        String name = authentication.getPrincipal().getAttribute("name");
        System.out.println("User email: " + email);
        System.out.println("User name: " + name);

        String chatId = (String) session.getAttribute("chatId");
        System.out.println("chatId: " + chatId);
        if (chatId != null) {
            OAuth2AuthorizedClient client = authorizedClientService.loadAuthorizedClient(
                    authentication.getAuthorizedClientRegistrationId(), authentication.getName());
            if (client != null) {
                String accessToken = client.getAccessToken().getTokenValue();
                String emailGoogle = authentication.getPrincipal().getAttribute("email");
                String firstName = authentication.getPrincipal().getAttribute("given_name");
                String lastName = authentication.getPrincipal().getAttribute("family_name");

                System.out.println("Access Token: " + accessToken);
                System.out.println("email Google: " + emailGoogle);
                System.out.println("firstName: " + firstName);
                System.out.println("lastName: " + lastName);

                long chatIdLong = Long.parseLong(chatId);
                telegramBotController.getUserDataMap().get(chatIdLong).setAccessToken(accessToken);
                telegramBotController.getUserDataMap().get(chatIdLong).setEmailGoogle(emailGoogle);
                telegramBotController.getUserDataMap().get(chatIdLong).setFirstName(firstName);
                telegramBotController.getUserDataMap().get(chatIdLong).setLastName(lastName);
                telegramBotController.getUserDataMap().get(chatIdLong).setState(UserData.State.WAITING_FOR_RESUME_ENABLE_AI);
                telegramBotController.sendMessage(chatIdLong, "Вы успешно авторизовались!\nEmail: " + email + "\nИмя: " + name);
                telegramBotController.sendMessage(chatIdLong, "Теперь необходимы ваши данные для создания резюме. Будут заданы несклоько вопросов. Итак...\n" +
                        "Желаете ли вы подключить ИИ для создания резюме? При согласии введите 'Да' или 'Нет' в случае отказа.");

                return "telegram/autorizationSuccess";
            } else {
                System.out.println("Client is null, unable to retrieve access token.");
                return "telegram/autorizationFailed";
            }
        }

        return "redirect:/convenient_job_search?authSuccess=true"; // Перенаправление на домашнюю страницу
    }
    //https://unlimitedpossibilities12.org/convenient_job_search

    @PostMapping("/api/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String idToken = body.get("idToken");
        String authCode = body.get("authCode"); // Получение authorization code

        System.out.println("idToken: " + idToken);
        System.out.println("authCode: " + authCode);

        // Проверка idToken
        GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(transport, jsonFactory)
                .setAudience(Collections.singletonList(clientId))
                .build();

        GoogleIdToken token;
        try {
            token = verifier.verify(idToken);
        } catch (GeneralSecurityException | IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Ошибка при проверке токена");
        }

        if (token != null) {
            // Получение access token через authorization code
            GoogleIdToken.Payload payload = token.getPayload();
            String email = payload.getEmail();
            String firstName = (String) payload.get("given_name");
            String lastName = (String) payload.get("family_name");

            String accessToken = exchangeCodeForAccessToken(authCode);

            System.out.println("accessToken: " + accessToken);
            System.out.println("email: " + email);
            System.out.println("firstName: " + firstName);
            System.out.println("lastName: " + lastName);

            // Формирование ответа
            return ResponseEntity.ok(Map.of(
                    "message", "Успешная аутентификация",
                    "accessToken", accessToken,
                    "email", email,
                    "firstName", firstName,
                    "lastName", lastName
            ));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Недействительный токен");
        }
    }

    // Метод для обмена authorization code на access token
    public String exchangeCodeForAccessToken(String code) {
        System.out.println("Method exchangeCodeForAccessToken is working...");
        try {
            GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                    transport,
                    jsonFactory,
                    clientId,
                    clientSecret, // Используем clientSecret в конструкторе
                    Arrays.asList("https://www.googleapis.com/auth/userinfo.email",
                            "https://www.googleapis.com/auth/gmail.send",
                            "email",
                            "profile"))
                    .build();

            GoogleTokenResponse tokenResponse = flow.newTokenRequest(code)
                    .setRedirectUri("https://unlimitedpossibilities12.org/oauth2/callback") // Это может быть любое значение, так как для мобильных приложений это не используется
                    .execute();

            return tokenResponse.getAccessToken();
        } catch (IOException e) {
            e.printStackTrace();
            return null; // Обработка ошибок
        }
    }
}
