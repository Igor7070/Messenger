package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.repo.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;

    public User createUser(User user) {
        String errorMessage = "";
        // Проверка на существование пользователя
        if (userRepository.findByUsername(user.getUsername()).isPresent()) {
            errorMessage = "User already exists";
            System.out.println(errorMessage);
            throw new RuntimeException(errorMessage);
        }
        // Проверка на валидность пароля
        if (!isValidPassword(user.getPassword())) {
            errorMessage = "Password must be at least 9 characters long, contain letters and numbers";
            System.out.println(errorMessage);
            throw new RuntimeException(errorMessage);
        }
        return userRepository.save(user);
    }

    public User loginUser(String username, String password) {
        String errorMessage = "";
        Optional<User> user = userRepository.findByUsername(username);
        if (user.isPresent() && user.get().getPassword().equals(password)) {
            return user.get();
        } else {
            errorMessage = "Invalid username or password";
            System.out.println(errorMessage);
            throw new RuntimeException(errorMessage);
        }
    }

    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElse(null); // Возвращает пользователя или null, если не найден
    }

    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    public User getUserById(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new RuntimeException("User not found"));
    }

    public User updateUser(User user) {
        return userRepository.save(user);
    }

    private boolean isValidPassword(String password) {
        return password.length() >= 9 && password.matches(".*[a-zA-Z].*") && password.matches(".*\\d.*");
    }

    public List<Chat> getPrivateChatsByUserId(Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user != null) {
            return user.getPrivateChats(); // Возвращаем список приватных чатов
        }
        return Collections.emptyList(); // Возвращаем пустой список, если пользователь не найден
    }

    public boolean deleteChat(Long userId, Long chatId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user != null) {
            List<Chat> chats = user.getPrivateChats();
            boolean isRemoved = chats.removeIf(chat -> chat.getId().equals(chatId));
            if (isRemoved) {
                userRepository.save(user); // Сохраняем обновленного пользователя в базе данных
                return true;
            }
        }
        return false;
    }
}
