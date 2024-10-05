package com.example.parsing_vacancies.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AuthController {
    @GetMapping("/login")
    public String login() {
        // Перенаправление на Google для аутентификации
        return "redirect:/oauth2/authorization/google";
    }

    @GetMapping("/oauth2/callback")
    public String oauth2Callback() {
        // Обработка данных пользователя после успешной аутентификации
        return "redirect:/convenient_job_search"; // Перенаправление на домашнюю страницу
    }
}
