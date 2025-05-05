package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.CallRequest;
import com.example.unl_pos12.model.messenger.signal.SignalMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
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
        System.out.println("roomId in method signal: " + roomId);
        System.out.println(String.format("Received signal from: %s, type: %s, sdp: %s, " +
                        "iceCandidate: %s", signalMessage.getFrom(), signalMessage.getSignal().getType(),
                signalMessage.getSignal().getSdp(), signalMessage.getSignal().getIceCandidate()));

        // Отправка сигнала другому участнику
        messagingTemplate.convertAndSend("/topic/room/" + roomId, signalMessage);
        System.out.println("Method signal worked success.");
    }

    @PostMapping("/call")
    public ResponseEntity<String> initiateCall(@RequestBody CallRequest callRequest) {
        System.out.println("Method initiateCall is working...");
        System.out.println("callRequest: " + callRequest);
        System.out.println("callRequest.getRecipientId(): " + callRequest.getRecipientId());
        // Устанавливаем type для начального запроса на звонок
        callRequest.setType("call");
        messagingTemplate.convertAndSend("/topic/calls/" + callRequest.getRecipientId(), callRequest);
        messagingTemplate.convertAndSend("/topic/room/" + callRequest.getRoomId(), callRequest);
        System.out.println("Method initiateCall worked success.");
        return ResponseEntity.ok("Call initiated");
    }

    @MessageMapping("/call/{recipientId}")
    @SendTo("/topic/calls/{recipientId}")
    public CallRequest handleCallMessage(@DestinationVariable String recipientId, @Payload CallRequest callRequest) {
        System.out.println("Received message for /app/call/" + recipientId + ": " + callRequest);
        return callRequest;
    }
}
