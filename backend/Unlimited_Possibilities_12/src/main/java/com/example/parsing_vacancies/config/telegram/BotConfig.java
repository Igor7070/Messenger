package com.example.parsing_vacancies.config.telegram;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class BotConfig {
    @Value("${telegrambot.name}")
    String botName;

    @Value("${telegrambot.api.token}")
    String token;

    public String getBotName() {
        return botName;
    }

    public String getToken() {
        return token;
    }
}
