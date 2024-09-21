package com.example.parsing_vacancies.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.completion.CompletionChoice;
import com.theokanning.openai.completion.CompletionRequest;
import com.theokanning.openai.completion.CompletionResult;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;
import com.theokanning.openai.service.OpenAiService;
import okhttp3.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.theokanning.openai.completion.chat.ChatMessage;


import java.io.FileInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Service
public class OpenAIService {
    @Value("${openai.api.key}")
    private String apiKey;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    public String generateCompletion(String prompt) {
        OpenAiService service = new OpenAiService(apiKey, DEFAULT_TIMEOUT);
        ChatCompletionRequest chatCompletionRequest = ChatCompletionRequest.builder()
                .model("gpt-4o")
                .messages(Collections.singletonList(
                        new ChatMessage("user", prompt)
                ))
                .maxTokens(3200)
                .temperature(0.9)
                .build();
        ChatCompletionResult chatCompletionResult = service.createChatCompletion(chatCompletionRequest);
        String response = chatCompletionResult.getChoices().get(0).getMessage().getContent();
        return response;
    }
}
