package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {
    @Autowired
    private UserService userService;

    @GetMapping
    public List<User> getAllUsers() {
        return userService.getAllUsers();
    }

    @PostMapping
    public User createUser(@RequestBody User user) {
        System.out.println("Created user: " + user.getUsername());
        return userService.createUser(user);
    }

    @PostMapping("/login")
    public User loginUser(@RequestBody Map<String, String> loginRequest) {
        String username = loginRequest.get("username");
        String password = loginRequest.get("password");
        return userService.loginUser(username, password);
    }
}
