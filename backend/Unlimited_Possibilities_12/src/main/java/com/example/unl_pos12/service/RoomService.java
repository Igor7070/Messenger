package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.UserDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RoomService {
    private final Map<String, List<UserDTO>> roomParticipants = new ConcurrentHashMap<>();

    @Autowired
    private UserService userService;

    public List<UserDTO> getParticipants(String roomId) {
        return roomParticipants.getOrDefault(roomId, new ArrayList<>());
    }

    public void addParticipant(String roomId, UserDTO user) {
        roomParticipants.computeIfAbsent(roomId, k -> new ArrayList<>()).add(user);
    }

    public void removeParticipant(String roomId, String userId) {
        roomParticipants.computeIfPresent(roomId, (k, v) -> {
            v.removeIf(user -> user.getId().equals(userId));
            return v.isEmpty() ? null : v;
        });
    }

    public void removeRoom(String roomId) {
        roomParticipants.remove(roomId);
    }
}