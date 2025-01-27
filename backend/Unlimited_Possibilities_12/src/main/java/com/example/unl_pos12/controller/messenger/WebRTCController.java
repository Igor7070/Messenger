package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.CallRequest;
import com.example.unl_pos12.model.messenger.SignalMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webrtc")
public class WebRTCController {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/signal/{roomId}")
    public void signal(@DestinationVariable String roomId, SignalMessage signalMessage) {
        System.out.println("Method signal is working...");
        // Отправка сигнала другому участнику
        messagingTemplate.convertAndSend("/topic/room/" + roomId, signalMessage);
        System.out.println("Method signal worked success.");
    }

    @PostMapping("/call")
    public ResponseEntity<String> initiateCall(@RequestBody CallRequest callRequest) {
        System.out.println("Method initiateCall is working...");
        // Отправляем уведомление о звонке
        messagingTemplate.convertAndSend("/topic/calls/" + callRequest.getRecipientId(), callRequest);

        // Можно отправить roomId, если это необходимо
        messagingTemplate.convertAndSend("/topic/room/" + callRequest.getRoomId(), callRequest);

        System.out.println("Method initiateCall worked success.");
        return ResponseEntity.ok("Call initiated");
    }
}
