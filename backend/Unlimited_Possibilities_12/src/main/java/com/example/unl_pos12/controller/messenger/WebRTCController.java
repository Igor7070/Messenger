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
import java.util.concurrent.*;

@RestController
@RequestMapping("/api/webrtc")
public class WebRTCController {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private CallService callService;

    private final ConcurrentHashMap<String, CallRequestInfo> pendingCalls = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1);

    // Класс для хранения информации о звонке
    private static class CallRequestInfo {
        String roomId;
        String callerId; // или initiatorId для групповых звонков
        String recipientId; // null для групповых звонков
        String[] recipientIds; // null для обычных звонков
        String callType;
        LocalDateTime timestamp;
        boolean responded; // Флаг ответа (accept/reject)
        boolean accepted; // NEW: Флаг принятия звонка
        ScheduledFuture<?> timeoutTask; // NEW: Для отмены таймаута

        CallRequestInfo(String roomId, String callerId, String recipientId, String[] recipientIds, String callType, ScheduledFuture<?> timeoutTask) {
            this.roomId = roomId;
            this.callerId = callerId;
            this.recipientId = recipientId;
            this.recipientIds = recipientIds;
            this.callType = callType;
            this.timestamp = LocalDateTime.now();
            this.responded = false;
            this.accepted = false;
            this.timeoutTask = timeoutTask;
        }
    }

    @MessageMapping("/signal/{roomId}")
    public void signal(@DestinationVariable String roomId, SignalMessage signalMessage) {
        System.out.println("Method signal is working...");
        System.out.println("roomId in method signal: " + roomId);
        System.out.println(String.format("Received signal from: %s, type: %s, sdp: %s, iceCandidate: %s",
                signalMessage.getFrom(), signalMessage.getSignal().getType(),
                signalMessage.getSignal().getSdp(), signalMessage.getSignal().getIceCandidate()));

        String signalType = signalMessage.getSignal().getType();
        String from = signalMessage.getFrom();
        String key = roomId + "-" + from;
        CallRequestInfo callInfo = pendingCalls.get(key);

        if ("acceptCall".equals(signalType)) {
            if (callInfo != null) {
                callInfo.responded = true;
                callInfo.accepted = true; // NEW: Устанавливаем accepted = true
                if (callInfo.timeoutTask != null) {
                    callInfo.timeoutTask.cancel(false); // NEW: Отменяем таймаут
                    System.out.println("Timeout cancelled for userId: " + from);
                }
            }
        } else if ("rejectCall".equals(signalType)) {
            if (callInfo != null) {
                callInfo.responded = true;
                if (callInfo.timeoutTask != null) {
                    callInfo.timeoutTask.cancel(false); // NEW: Отменяем таймаут
                    System.out.println("Timeout cancelled for userId: " + from);
                }
            }
        } else if ("endCall".equals(signalType)) {
            System.out.println("Processing endCall for roomId: " + roomId + ", pendingCalls: " + pendingCalls.keySet()); // NEW: Лог для отладки
            pendingCalls.forEach((k, info) -> {
                if (k.startsWith(roomId + "-") && !info.accepted) {
                    String recipientId = k.split("-")[1];
                    // Пропускаем инициатора звонка
                    if (!recipientId.equals(info.callerId)) {
                        saveMissedCall(info, recipientId);
                        System.out.println("Missed call saved for userId: " + recipientId + " due to endCall");
                        // Отменяем таймаут для этого получателя
                        if (info.timeoutTask != null) {
                            info.timeoutTask.cancel(false);
                            System.out.println("Timeout cancelled for userId: " + recipientId + " due to endCall");
                        }
                    }
                }
            });
            pendingCalls.entrySet().removeIf(entry -> entry.getKey().startsWith(roomId + "-"));
            System.out.println("Pending calls cleared for roomId: " + roomId);
        }

        messagingTemplate.convertAndSend("/topic/room/" + roomId, signalMessage);
        System.out.println("Method signal worked success.");
    }

    @PostMapping("/call")
    public ResponseEntity<String> initiateCall(@RequestBody CallRequest callRequest) {
        System.out.println("Method initiateCall is working...");
        System.out.println("callRequest: " + callRequest);
        System.out.println("callRequest.getRecipientId(): " + callRequest.getRecipientId());

        if (callRequest.getType() == null || (!callRequest.getType().equals("voice") && !callRequest.getType().equals("video") && !callRequest.getType().equals("translate"))) {
            System.out.println("Invalid or missing call type, defaulting to 'voice'");
            callRequest.setType("voice");
        }

        String key = callRequest.getRoomId() + "-" + callRequest.getRecipientId();
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            CallRequestInfo callInfo = pendingCalls.get(key);
            if (callInfo != null && !callInfo.responded && !callInfo.accepted) {
                saveMissedCall(callInfo, callRequest.getRecipientId());
                pendingCalls.remove(key);
            }
        }, 30, TimeUnit.SECONDS);

        pendingCalls.put(key, new CallRequestInfo(
                callRequest.getRoomId(),
                callRequest.getCallerId(),
                callRequest.getRecipientId(),
                null,
                callRequest.getType().equals("translate") ? "translated" : callRequest.getType(),
                timeoutTask
        ));

        System.out.println("Added to pendingCalls: key=" + key + ", callInfo=" + pendingCalls.get(key));

        messagingTemplate.convertAndSend("/topic/calls/" + callRequest.getRecipientId(), callRequest);
        messagingTemplate.convertAndSend("/topic/room/" + callRequest.getRoomId(), callRequest);
        System.out.println("Method initiateCall worked success.");
        return ResponseEntity.ok("Call initiated");
    }

    @MessageMapping("/call/{recipientId}")
    @SendTo("/topic/calls/{recipientId}")
    public CallRequest handleCallMessage(@DestinationVariable String recipientId, @Payload CallRequest callRequest) {
        System.out.println("Received message for /app/call/" + recipientId + ": " + callRequest);

        String key = callRequest.getRoomId() + "-" + recipientId;
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            CallRequestInfo callInfo = pendingCalls.get(key);
            if (callInfo != null && !callInfo.responded && !callInfo.accepted) {
                saveMissedCall(callInfo, recipientId);
                pendingCalls.remove(key);
            }
        }, 30, TimeUnit.SECONDS);

        pendingCalls.put(key, new CallRequestInfo(
                callRequest.getRoomId(),
                callRequest.getCallerId(),
                recipientId,
                null,
                callRequest.getType().equals("translate") ? "translated" : callRequest.getType(),
                timeoutTask
        ));

        return callRequest;
    }

    @PostMapping("/groupCall")
    public ResponseEntity<String> initiateGroupCall(@RequestBody GroupCallRequest groupCallRequest) {
        System.out.println("Method initiateGroupCall is working...");
        System.out.println("groupCallRequest: " + groupCallRequest);

        if (groupCallRequest.getType() == null) {
            System.out.println("Invalid or missing call type, defaulting to 'group'");
            groupCallRequest.setType("group");
        }

        for (String recipientId : groupCallRequest.getRecipientIds()) {
            String key = groupCallRequest.getRoomId() + "-" + recipientId;
            ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
                CallRequestInfo callInfo = pendingCalls.get(key);
                if (callInfo != null && !callInfo.responded && !callInfo.accepted) {
                    saveMissedCall(callInfo, recipientId);
                    pendingCalls.remove(key);
                }
            }, 30, TimeUnit.SECONDS);

            pendingCalls.put(key, new CallRequestInfo(
                    groupCallRequest.getRoomId(),
                    groupCallRequest.getInitiatorId(),
                    null,
                    groupCallRequest.getRecipientIds().toArray(new String[0]),
                    groupCallRequest.getType(),
                    timeoutTask
            ));
        }

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

        String key = groupCallRequest.getRoomId() + "-" + recipientId;
        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            CallRequestInfo callInfo = pendingCalls.get(key);
            if (callInfo != null && !callInfo.responded && !callInfo.accepted) {
                saveMissedCall(callInfo, recipientId);
                pendingCalls.remove(key);
            }
        }, 30, TimeUnit.SECONDS);

        pendingCalls.put(key, new CallRequestInfo(
                groupCallRequest.getRoomId(),
                groupCallRequest.getInitiatorId(),
                null,
                groupCallRequest.getRecipientIds().toArray(new String[0]),
                groupCallRequest.getType(),
                timeoutTask
        ));

        return groupCallRequest;
    }

    @MessageMapping("/groupSignal/{roomId}")
    public void groupSignal(@DestinationVariable String roomId, SignalMessage signalMessage) {
        System.out.println("Method groupSignal is working...");
        System.out.println("roomId in method groupSignal: " + roomId);
        System.out.println(String.format("Received group signal from: %s, to: %s, type: %s, sdp: %s, iceCandidate: %s",
                signalMessage.getFrom(), signalMessage.getTo(), signalMessage.getSignal().getType(),
                signalMessage.getSignal().getSdp(), signalMessage.getSignal().getIceCandidate()));

        String signalType = signalMessage.getSignal().getType();
        String from = signalMessage.getFrom();
        String key = roomId + "-" + from;
        CallRequestInfo callInfo = pendingCalls.get(key);

        if ("acceptCall".equals(signalType)) {
            if (callInfo != null) {
                callInfo.responded = true;
                callInfo.accepted = true; // NEW: Устанавливаем accepted = true
                if (callInfo.timeoutTask != null) {
                    callInfo.timeoutTask.cancel(false); // NEW: Отменяем таймаут
                    System.out.println("Timeout cancelled for userId: " + from);
                }
            }
        } else if ("rejectCall".equals(signalType)) {
            if (callInfo != null) {
                callInfo.responded = true;
                if (callInfo.timeoutTask != null) {
                    callInfo.timeoutTask.cancel(false); // NEW: Отменяем таймаут
                    System.out.println("Timeout cancelled for userId: " + from);
                }
            }
        } else if ("endCall".equals(signalType)) {
            pendingCalls.forEach((k, info) -> {
                if (k.startsWith(roomId + "-") && !info.responded && !info.accepted) {
                    String recipientId = k.split("-")[1];
                    saveMissedCall(info, recipientId);
                }
            });
            pendingCalls.entrySet().removeIf(entry -> entry.getKey().startsWith(roomId + "-"));
        }

        messagingTemplate.convertAndSend("/topic/room/" + roomId, signalMessage);
        System.out.println("Method groupSignal worked success.");
    }

    private void saveMissedCall(CallRequestInfo callInfo, String recipientId) {
        try {
            Call call = new Call();
            call.setCaller(callInfo.callerId);
            call.setDirection("incoming");
            call.setStatus("missed");
            call.setCallType(callInfo.callType);
            call.setTimestamp(callInfo.timestamp);
            call.setRoomId(callInfo.roomId); // NEW: Устанавливаем roomId

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
