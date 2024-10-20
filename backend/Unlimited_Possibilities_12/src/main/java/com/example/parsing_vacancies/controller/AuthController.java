package com.example.parsing_vacancies.controller;

import com.example.parsing_vacancies.controller.telegram.TelegramBotController;
import com.example.parsing_vacancies.service.AuthenticationDebugService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {
    @Autowired
    private AuthenticationDebugService authDebugService;
    @Autowired
    private OAuth2AuthorizedClientService authorizedClientService; // Сервис для получения токена
    @Autowired
    private TelegramBotController telegramBotController;

    @GetMapping("/login")
    public String login(HttpSession session, @RequestParam(required = false) String chatId) {
        System.out.println("Method login working...");
        System.out.println("chatId: " + chatId);
        session.setAttribute("chatId", chatId);
        // Перенаправление на Google для аутентификации
        System.out.println("Autorization");
        return "redirect:/oauth2/authorization/google";
    }

    @GetMapping("/oauth2/callback")
    public String oauth2Callback(OAuth2AuthenticationToken authentication, HttpSession session) {
        // Проверяем текущую аутентификацию
        authDebugService.logCurrentAuthentication();

        if (authentication == null) {
            System.out.println("Authentication is null");
            return "redirect:/login"; // Перенаправление на страницу логина
            //return "redirect:/convenient_job_search";
        }

        System.out.println("Success authorization");
        System.out.println("Authentication details: " + authentication.toString());
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
                System.out.println("Access Token: " + accessToken); // Вывод токена в консоль
            } else {
                System.out.println("Client is null, unable to retrieve access token.");
            }
            long chatIdLong = Long.parseLong(chatId);
            telegramBotController.sendMessage(chatIdLong, "Вы успешно авторизовались!\nEmail: " + email + "\nИмя: " + name);
            return "";
        }

        return "redirect:/convenient_job_search"; // Перенаправление на домашнюю страницу
    }
    //https://unlimitedpossibilities12.org/convenient_job_search
}
