package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
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

    @PostMapping("/create")
    public User createUserWithFile(
            @RequestParam("username") String username,
            @RequestParam("password") String password,
            @RequestParam(value = "bio", required = false) String info,
            @RequestParam(value = "avatar", required = false) MultipartFile avatar) {

        User user = new User();
        user.setUsername(username);
        user.setPassword(password);

        // Устанавливаем данные о себе, если они переданы
        if (info != null) {
            user.setInfo(info);
        }

        // Сохраняем файл, если он загружен
        if (avatar != null && !avatar.isEmpty()) {
            String avatarPath = saveAvatar(avatar); // Метод сохранения аватара
            user.setAvatar(avatarPath);
        }

        System.out.println("Created user: " + user.getUsername());
        return userService.createUser(user);
    }

    // Реализация метода saveAvatar
    private String saveAvatar(MultipartFile avatar) {
        // Указываем путь к папке для сохранения аватаров
        String uploadDir = "src/main/resources/static/avatars/";
        String filePath = uploadDir + avatar.getOriginalFilename(); // Полный путь к файлу

        try {
            // Сохраняем файл в указанную папку
            avatar.transferTo(new File(filePath));
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Возвращаем относительный путь для хранения в базе данных
        return "/avatars/" + avatar.getOriginalFilename(); // Относительный путь
    }

    @PostMapping("/login")
    public User loginUser(@RequestBody Map<String, String> loginRequest) {
        String username = loginRequest.get("username");
        String password = loginRequest.get("password");
        return userService.loginUser(username, password);
    }
}
