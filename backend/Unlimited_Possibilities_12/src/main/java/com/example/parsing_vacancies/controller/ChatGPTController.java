package com.example.parsing_vacancies.controller;

import com.example.parsing_vacancies.service.OpenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

@Controller
public class ChatGPTController {
    @Autowired
    private OpenAIService openAIService;
    private final List<String> chatMessages = new ArrayList<>();


    @GetMapping("/communicating_with_a_advanced_ai_model")
    public String chatGPT(Model model) {
        model.addAttribute("title", "Продвинутая модель");
        model.addAttribute("chatMessages", chatMessages);
        return "advancedModel";
    }

    @PostMapping("/communicating_with_a_advanced_ai_model")
    public String generateCompletion(@RequestParam(name = "prompt") String prompt, Model model) {
        String result = openAIService.generateCompletion(prompt);
        chatMessages.add(prompt);
        chatMessages.add(result);
        model.addAttribute("chatMessages", chatMessages);
        return "advancedModel";
    }
}
