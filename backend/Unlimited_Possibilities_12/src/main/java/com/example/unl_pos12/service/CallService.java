package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.Call;
import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.repo.CallRepository;
import com.example.unl_pos12.repo.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    public void deleteCall(Long id, Long userId) {
        Call call = getCallById(id);
        if (!call.getUser().getId().equals(userId)) {
            throw new IllegalStateException("User is not authorized to delete this call");
        }
        callRepository.delete(call);
    }
}
