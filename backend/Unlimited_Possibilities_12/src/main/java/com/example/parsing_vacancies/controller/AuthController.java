package com.example.parsing_vacancies.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AuthController {

    @Value("${GOOGLE_CLIENT_ID}")
    private String googleClientId;

    @Value("${GOOGLE_CLIENT_SECRET}")
    private String googleClientSecret;

    @GetMapping("/login")
    public String login() {
        // Перенаправление на Google для аутентификации
        //System.out.println("GOOGLE_CLIENT_ID: " + googleClientId);
        //System.out.println("GOOGLE_CLIENT_SECRET: " + googleClientSecret);
        return "redirect:/oauth2/authorization/google";
    }

    @GetMapping("/oauth2/callback")
    public String oauth2Callback() {
        // Обработка данных пользователя после успешной аутентификации
        return "redirect:/convenient_job_search"; // Перенаправление на домашнюю страницу
    }
}
