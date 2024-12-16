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

    private boolean isValidPassword(String password) {
        return password.length() >= 9 && password.matches(".*[a-zA-Z].*") && password.matches(".*\\d.*");
    }
}
