package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.Call;
import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.repo.CallRepository;
import com.example.unl_pos12.repo.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class CallService {
    private final CallRepository callRepository;
    private final UserRepository userRepository;

    @Autowired
    public CallService(CallRepository callRepository, UserRepository userRepository) {
        this.callRepository = callRepository;
        this.userRepository = userRepository;
    }

    // NEW: Проверка существования звонка
    public boolean existsByUserIdAndRoomIdAndTimestamp(Long userId, String roomId, LocalDateTime timestamp) {
        return callRepository.existsByUserIdAndRoomIdAndTimestampBetween(
                userId, roomId, timestamp.minusSeconds(30), timestamp.plusSeconds(30));
    }

    // Создать звонок
    public Call createCall(Call call, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + userId));

        // NEW: Проверка уникальности звонка
        if (call.getRoomId() != null && existsByUserIdAndRoomIdAndTimestamp(userId, call.getRoomId(), call.getTimestamp())) {
            System.out.println("Call already exists for userId: " + userId + ", roomId: " + call.getRoomId());
            return null; // или выбросить исключение, в зависимости от требований
        }

        call.setUser(user);
        return callRepository.save(call);
    }

    // Получить звонки пользователя
    public List<Call> getUserCalls(Long userId) {
        return callRepository.findByUserId(userId);
    }

    // Получить звонок по ID
    public Call getCallById(Long id) {
        return callRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Call not found with id: " + id));
    }

    // Удалить звонок
    public java.util.Map<Long, Long> deleteCall(Long id, Long userId) {
        return deleteCall(id, userId, false);
    }

    /**
     * Удалить звонок.
     *
     * Своя запись убирается всегда. Если попросили убрать и у собеседника,
     * находим его запись об этом же звонке: комната та же, время то же,
     * хозяин — другой.
     *
     * Прежде парную искали по одной только комнате, а комната у всех
     * звонков с одним человеком одна: убирая один звонок «у обоих», мы
     * сносили собеседнику всю переписку звонками. Потому и время: у двух
     * сторон оно расходится на доли секунды, но не на минуты.
     */
    public java.util.Map<Long, Long> deleteCall(Long id, Long userId, boolean forBoth) {
        Call call = getCallById(id);
        if (!call.getUser().getId().equals(userId)) {
            throw new IllegalStateException("User is not authorized to delete this call");
        }
        String roomId = call.getRoomId();
        java.time.LocalDateTime когда = call.getTimestamp();
        /* Кому о чём сказать: хозяин записи — номер записи. По этому списку
           обе стороны уберут карточку у себя, не дожидаясь перезахода. */
        java.util.Map<Long, Long> убрано = new java.util.LinkedHashMap<>();
        убрано.put(userId, id);
        callRepository.delete(call);
        if (forBoth && roomId != null && !roomId.isEmpty() && когда != null) {
            for (Call парная : callRepository.findByRoomId(roomId)) {
                if (парная.getId().equals(id)) continue;
                if (парная.getUser() == null || парная.getUser().getId().equals(userId)) continue;
                if (парная.getTimestamp() == null) continue;
                long секунд = Math.abs(java.time.Duration.between(когда, парная.getTimestamp()).getSeconds());
                if (секунд <= 5) {
                    убрано.put(парная.getUser().getId(), парная.getId());
                    callRepository.delete(парная);
                    break;   // запись у собеседника одна
                }
            }
        }
        return убрано;
    }

    @Transactional
    @Scheduled(cron = "0 0 2 * * ?")
    public void deleteOldCalls() {
        LocalDateTime threshold = LocalDateTime.now(ZoneId.of("Europe/Kiev")).minusDays(7);
        callRepository.deleteCallsOlderThan(threshold);
        System.out.println("Deleted calls older than: " + threshold);
    }
}
