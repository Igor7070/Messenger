package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.CallRequest;
import com.example.unl_pos12.model.messenger.GroupCallRequest;
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

        // Проверяем, что type установлен клиентом
        if (callRequest.getType() == null || (!callRequest.getType().equals("voice") && !callRequest.getType().equals("video") && !callRequest.getType().equals("translate"))) {
            System.out.println("Invalid or missing call type, defaulting to 'voice'");
            callRequest.setType("voice"); // Устанавливаем по умолчанию voice, если type некорректен
        }

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

    // Новые методы для групповых звонков
    @PostMapping("/groupCall")
    public ResponseEntity<String> initiateGroupCall(@RequestBody GroupCallRequest groupCallRequest) {
        System.out.println("Method initiateGroupCall is working...");
        System.out.println("groupCallRequest: " + groupCallRequest);

        if (groupCallRequest.getType() == null) {
            System.out.println("Invalid or missing call type, defaulting to 'group'");
            groupCallRequest.setType("group");
        }

        // Отправка приглашения каждому участнику
        for (String recipientId : groupCallRequest.getRecipientIds()) {
            messagingTemplate.convertAndSend("/topic/groupCalls/" + recipientId, groupCallRequest);
        }
        messagingTemplate.convertAndSend("/topic/room/" + groupCallRequest.getRoomId(), groupCallRequest);
        System.out.println("Method initiateGroupCall worked success.");
        return ResponseEntity.ok("Group call initiated");
    }

    @MessageMapping("/groupCall/{recipientId}")
    @SendTo("/topic/groupCalls/{recipientId}")
    public GroupCallRequest handleGroupCallMessage(@DestinationVariable String recipientId, @Payload GroupCallRequest groupCallRequest) {
        System.out.println("Received message for /app/groupCall/" + recipientId + ": " + groupCallRequest);
        return groupCallRequest;
    }

    @MessageMapping("/groupSignal/{roomId}")
    public void groupSignal(@DestinationVariable String roomId, SignalMessage signalMessage) {
        System.out.println("Method groupSignal is working...");
        System.out.println("roomId in method groupSignal: " + roomId);
        System.out.println(String.format("Received group signal from: %s, to: %s, type: %s, sdp: %s, iceCandidate: %s",
                signalMessage.getFrom(), signalMessage.getTo(), signalMessage.getSignal().getType(),
                signalMessage.getSignal().getSdp(), signalMessage.getSignal().getIceCandidate()));

        messagingTemplate.convertAndSend("/topic/room/" + roomId, signalMessage);
        System.out.println("Method groupSignal worked success.");
    }
}
