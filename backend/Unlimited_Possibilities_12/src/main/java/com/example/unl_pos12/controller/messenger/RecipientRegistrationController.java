package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.service.OpenAIService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

@Controller
public class RecipientRegistrationController {
    @Autowired
    private OpenAIService openAIService;
    private final ObjectMapper mapper = new ObjectMapper();

    @MessageMapping("/register-recipient/{roomId}")
    public void handleRecipientRegistration(@DestinationVariable String roomId, @Payload String message) {
        try {
            var json = mapper.readTree(message);
            String sessionId = json.get("sessionId").asText();
            String recipientId = json.get("recipientId").asText();
            openAIService.registerRecipient(roomId, sessionId, recipientId);
            System.out.println("Registered recipient for roomId " + roomId + ": sessionId=" + sessionId + ", recipientId=" + recipientId);
        } catch (Exception e) {
            System.out.println("Error registering recipient for roomId " + roomId + ": " + e.getMessage());
        }
    }
}