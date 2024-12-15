package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.repo.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService {
    @Autowired
    private UserRepository userRepository;

    public User createUser(User user) {
        // Проверка на существование пользователя
        if (userRepository.findByUsername(user.getUsername()).isPresent()) {
            throw new RuntimeException("User already exists");
        }
        // Проверка на валидность пароля
        if (!isValidPassword(user.getPassword())) {
            throw new RuntimeException("Password must be at least 9 characters long, contain letters and numbers");
        }
        return userRepository.save(user);
    }

    public User loginUser(String username, String password) {
        Optional<User> user = userRepository.findByUsername(username);
        if (user.isPresent() && user.get().getPassword().equals(password)) {
            return user.get();
        } else {
            throw new RuntimeException("Invalid username or password");
        }
    }

    private boolean isValidPassword(String password) {
        return password.length() >= 9 && password.matches(".*[a-zA-Z].*") && password.matches(".*\\d.*");
    }
}
