package com.example.parsing_vacancies.controller;

import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AuthController {
    @GetMapping("/login")
    public String login() {
        // Перенаправление на Google для аутентификации
        System.out.println("Autorization");
        return "redirect:/oauth2/authorization/google";
    }

    @GetMapping("/oauth2/callback")
    public String oauth2Callback(OAuth2AuthenticationToken authentication) {
        if (authentication == null) {
            System.out.println("Authentication is null");
            //return "redirect:/login"; // Перенаправление на страницу логина
            return "redirect:/convenient_job_search";
        }

        System.out.println("Success authorization");
        System.out.println("Authentication details: " + authentication.toString());

        String email = authentication.getPrincipal().getAttribute("email");
        String name = authentication.getPrincipal().getAttribute("name");

        System.out.println("User email: " + email);
        System.out.println("User name: " + name);

        return "redirect:/convenient_job_search"; // Перенаправление на домашнюю страницу
    }
    //https://unlimitedpossibilities12.org/convenient_job_search
}
