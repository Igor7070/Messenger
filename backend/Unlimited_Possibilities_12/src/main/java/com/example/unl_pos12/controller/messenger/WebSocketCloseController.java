package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.service.OpenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.util.Map;
import java.util.logging.Logger;

@Controller
public class WebSocketCloseController {
    private static final Logger LOGGER = Logger.getLogger(WebSocketCloseController.class.getName());
    @Autowired
    private OpenAIService openAIService;

    @MessageMapping("/close-websocket")
    public void handleCloseWebSocket(@Payload Map<String, String> payload) {
        String roomId = payload.get("roomId");
        if (roomId != null) {
            openAIService.closeWebSocket(roomId);
            LOGGER.info("Received closeWebSocket signal for roomId: " + roomId);
        } else {
            LOGGER.warning("Received closeWebSocket signal with null roomId");
        }
    }
}
