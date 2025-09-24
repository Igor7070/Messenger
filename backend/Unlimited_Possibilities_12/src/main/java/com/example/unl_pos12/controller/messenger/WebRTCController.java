package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Call;
import com.example.unl_pos12.model.messenger.CallRequest;
import com.example.unl_pos12.model.messenger.GroupCallRequest;
import com.example.unl_pos12.model.messenger.signal.SignalMessage;
import com.example.unl_pos12.service.CallService;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/webrtc")
public class WebRTCController {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private CallService callService; // NEW: Добавляем CallService

    private final ConcurrentHashMap<String, CallRequestInfo> pendingCalls = new ConcurrentHashMap<>(); // NEW: Хранилище активных звонков
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1); // NEW: Для таймаута

    // NEW: Класс для хранения информации о звонке
    private static class CallRequestInfo {
        String roomId;
        String callerId; // или initiatorId для групповых звонков
        String recipientId; // null для групповых звонков
        String[] recipientIds; // null для обычных звонков
        String callType;
        LocalDateTime timestamp;
        boolean responded; // Флаг ответа (accept/reject)

        CallRequestInfo(String roomId, String callerId, String recipientId, String[] recipientIds, String callType) {
            this.roomId = roomId;
            this.callerId = callerId;
            this.recipientId = recipientId;
            this.recipientIds = recipientIds;
            this.callType = callType;
            this.timestamp = LocalDateTime.now();
            this.responded = false;
        }
    }

    @MessageMapping("/signal/{roomId}")
    public void signal(@DestinationVariable String roomId, SignalMessage signalMessage) {
        System.out.println("Method signal is working...");
        System.out.println("roomId in method signal: " + roomId);
        System.out.println(String.format("Received signal from: %s, type: %s, sdp: %s, iceCandidate: %s",
                signalMessage.getFrom(), signalMessage.getSignal().getType(),
                signalMessage.getSignal().getSdp(), signalMessage.getSignal().getIceCandidate()));

        // NEW: Обработка сигналов acceptCall/rejectCall/endCall
        String signalType = signalMessage.getSignal().getType();
        String from = signalMessage.getFrom();
        if ("acceptCall".equals(signalType) || "rejectCall".equals(signalType)) {
            String key = roomId + "-" + from;
            CallRequestInfo callInfo = pendingCalls.get(key);
            if (callInfo != null) {
                callInfo.responded = true; // Помечаем, что получатель ответил
            }
        } else if ("endCall".equals(signalType)) {
            // Проверяем все звонки с этим roomId
            pendingCalls.forEach((key, callInfo) -> {
                if (key.startsWith(roomId + "-") && !callInfo.responded) {
                    String recipientId = key.split("-")[1];
                    saveMissedCall(callInfo, recipientId);
                }
            });
            // Удаляем все звонки с этим roomId
            pendingCalls.entrySet().removeIf(entry -> entry.getKey().startsWith(roomId + "-"));
        }

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

        // NEW: Сохраняем звонок в pendingCalls
        String key = callRequest.getRoomId() + "-" + callRequest.getRecipientId();
        pendingCalls.put(key, new CallRequestInfo(
                callRequest.getRoomId(),
                callRequest.getCallerId(),
                callRequest.getRecipientId(),
                null,
                callRequest.getType().equals("translate") ? "translated" : callRequest.getType()
        ));

        // NEW: Устанавливаем таймаут на 30 секунд
        scheduler.schedule(() -> {
            CallRequestInfo callInfo = pendingCalls.get(key);
            if (callInfo != null && !callInfo.responded) {
                saveMissedCall(callInfo, callRequest.getRecipientId());
                pendingCalls.remove(key);
            }
        }, 30, TimeUnit.SECONDS);

        messagingTemplate.convertAndSend("/topic/calls/" + callRequest.getRecipientId(), callRequest);
        messagingTemplate.convertAndSend("/topic/room/" + callRequest.getRoomId(), callRequest);
        System.out.println("Method initiateCall worked success.");
        return ResponseEntity.ok("Call initiated");
    }

    @MessageMapping("/call/{recipientId}")
    @SendTo("/topic/calls/{recipientId}")
    public CallRequest handleCallMessage(@DestinationVariable String recipientId, @Payload CallRequest callRequest) {
        System.out.println("Received message for /app/call/" + recipientId + ": " + callRequest);

        // NEW: Сохраняем звонок в pendingCalls
        String key = callRequest.getRoomId() + "-" + recipientId;
        pendingCalls.put(key, new CallRequestInfo(
                callRequest.getRoomId(),
                callRequest.getCallerId(),
                recipientId,
                null,
                callRequest.getType().equals("translate") ? "translated" : callRequest.getType()
        ));

        // NEW: Устанавливаем таймаут на 30 секунд
        scheduler.schedule(() -> {
            CallRequestInfo callInfo = pendingCalls.get(key);
            if (callInfo != null && !callInfo.responded) {
                saveMissedCall(callInfo, recipientId);
                pendingCalls.remove(key);
            }
        }, 30, TimeUnit.SECONDS);

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

        // NEW: Сохраняем звонок для каждого получателя
        for (String recipientId : groupCallRequest.getRecipientIds()) {
            String key = groupCallRequest.getRoomId() + "-" + recipientId;
            pendingCalls.put(key, new CallRequestInfo(
                    groupCallRequest.getRoomId(),
                    groupCallRequest.getInitiatorId(),
                    null,
                    groupCallRequest.getRecipientIds().toArray(new String[0]),
                    groupCallRequest.getType()
            ));

            // NEW: Устанавливаем таймаут на 30 секунд для каждого получателя
            scheduler.schedule(() -> {
                CallRequestInfo callInfo = pendingCalls.get(key);
                if (callInfo != null && !callInfo.responded) {
                    saveMissedCall(callInfo, recipientId);
                    pendingCalls.remove(key);
                }
            }, 30, TimeUnit.SECONDS);
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

        // NEW: Сохраняем звонок в pendingCalls
        String key = groupCallRequest.getRoomId() + "-" + recipientId;
        pendingCalls.put(key, new CallRequestInfo(
                groupCallRequest.getRoomId(),
                groupCallRequest.getInitiatorId(),
                null,
                groupCallRequest.getRecipientIds().toArray(new String[0]),
                groupCallRequest.getType()
        ));

        // NEW: Устанавливаем таймаут на 30 секунд
        scheduler.schedule(() -> {
            CallRequestInfo callInfo = pendingCalls.get(key);
            if (callInfo != null && !callInfo.responded) {
                saveMissedCall(callInfo, recipientId);
                pendingCalls.remove(key);
            }
        }, 30, TimeUnit.SECONDS);

        return groupCallRequest;
    }

    @MessageMapping("/groupSignal/{roomId}")
    public void groupSignal(@DestinationVariable String roomId, SignalMessage signalMessage) {
        System.out.println("Method groupSignal is working...");
        System.out.println("roomId in method groupSignal: " + roomId);
        System.out.println(String.format("Received group signal from: %s, to: %s, type: %s, sdp: %s, iceCandidate: %s",
                signalMessage.getFrom(), signalMessage.getTo(), signalMessage.getSignal().getType(),
                signalMessage.getSignal().getSdp(), signalMessage.getSignal().getIceCandidate()));

        // NEW: Обработка сигналов acceptCall/rejectCall/endCall
        String signalType = signalMessage.getSignal().getType();
        String from = signalMessage.getFrom();
        if ("acceptCall".equals(signalType) || "rejectCall".equals(signalType)) {
            String key = roomId + "-" + from;
            CallRequestInfo callInfo = pendingCalls.get(key);
            if (callInfo != null) {
                callInfo.responded = true; // Помечаем, что получатель ответил
            }
        } else if ("endCall".equals(signalType)) {
            // Проверяем все звонки с этим roomId
            pendingCalls.forEach((key, callInfo) -> {
                if (key.startsWith(roomId + "-") && !callInfo.responded) {
                    String recipientId = key.split("-")[1];
                    saveMissedCall(callInfo, recipientId);
                }
            });
            // Удаляем все звонки с этим roomId
            pendingCalls.entrySet().removeIf(entry -> entry.getKey().startsWith(roomId + "-"));
        }

        messagingTemplate.convertAndSend("/topic/room/" + roomId, signalMessage);
        System.out.println("Method groupSignal worked success.");
    }

    // NEW: Метод для сохранения пропущенного звонка
    private void saveMissedCall(CallRequestInfo callInfo, String recipientId) {
        try {
            Call call = new Call();
            call.setCaller(callInfo.callerId);
            call.setDirection("incoming");
            call.setStatus("missed");
            call.setCallType(callInfo.callType);
            call.setTimestamp(callInfo.timestamp);

            if ("group".equals(callInfo.callType)) {
                call.setParticipants(new ArrayList<>(List.of(callInfo.recipientIds)));
            } else {
                call.setReceiver(recipientId);
            }

            callService.createCall(call, Long.parseLong(recipientId));
            System.out.println("Missed call saved for userId: " + recipientId + ", callType: " + callInfo.callType);
        } catch (Exception e) {
            System.err.println("Error saving missed call for userId: " + recipientId + ": " + e.getMessage());
        }
    }
}
