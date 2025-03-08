package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @GetMapping("/{id}") // Получение пользователя по ID
    public User getUserById(@PathVariable Long id) {
        return userService.getUserById(id);
    }

    @GetMapping("/check-avatar/{filename}")
    public ResponseEntity<String> checkAvatar(@PathVariable String filename) {
        // Указываем абсолютный путь к директории для аватаров в проекте
        String filePath = new File("src/main/resources/static/avatars/" + filename).getAbsolutePath();
        File file = new File(filePath);

        if (file.exists()) {
            return ResponseEntity.ok("File exists: " + file.getAbsolutePath());
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("File not found");
        }
    }

    @GetMapping("/{id}/avatar")
    public ResponseEntity<String> getUserAvatar(@PathVariable Long id) {
        User user = userService.getUserById(id);
        if (user != null && user.getAvatar() != null) {
            // Возвращаем имя файла аватара
            String fileName = user.getAvatar().substring(user.getAvatar().lastIndexOf('/') + 1);
            return ResponseEntity.ok(fileName);
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Avatar not found");
        }
    }

    @GetMapping("/by-username/{username}")
    public ResponseEntity<User> getUserByUsername(@PathVariable String username) {
        User user = userService.getUserByUsername(username);
        if (user != null) {
            return ResponseEntity.ok(user);
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
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
            String avatarPath = saveAvatar(username, avatar); // Метод сохранения аватара
            user.setAvatar(avatarPath);
        }

        System.out.println("Created user: " + user.getUsername());
        return userService.createUser(user);
    }

    // Реализация метода saveAvatar...
    private String saveAvatar(String username, MultipartFile avatar) {
        // Используем абсолютный путь к директории для аватаров
        String uploadDir = new File("src/main/resources/static/avatars/").getAbsolutePath();

        // Создаем директорию, если она не существует
        File directory = new File(uploadDir);
        if (!directory.exists()) {
            directory.mkdirs(); // Создает все необходимые родительские директории
            System.out.println("Created directory: " + uploadDir);
        }

        String originalFilename = avatar.getOriginalFilename();
        String extension = originalFilename != null ? originalFilename.substring(originalFilename.lastIndexOf(".")) : ".png";
        String transliteratedUsername = transliterate(username);
        String fileName = transliteratedUsername + extension;
        String filePath = uploadDir + File.separator + fileName;

        try {
            avatar.transferTo(new File(filePath));
            System.out.println("Avatar saved to: " + filePath);
        } catch (IOException e) {
            System.out.println("Error saving avatar: " + e.getMessage());
            e.printStackTrace();
        }

        return "/avatars/" + fileName;
    }

    @PutMapping("/{id}") // Обновление данных пользователя
    public User updateUser(@PathVariable Long id,
                           @RequestParam(value = "info", required = false) String info,
                           @RequestParam(value = "avatar", required = false) MultipartFile avatar) {
        User user = userService.getUserById(id);
        if (user != null) {
            if (info != null) {
                user.setInfo(info);
            }
            if (avatar != null && !avatar.isEmpty()) {
                String avatarPath = saveAvatar(user.getUsername(), avatar);
                user.setAvatar(avatarPath);
            }
            return userService.updateUser(user); // Обновляем пользователя
        } else {
            throw new RuntimeException("User not found");
        }
    }

    @PostMapping("/login")
    public User loginUser(@RequestBody Map<String, String> loginRequest) {
        String username = loginRequest.get("username");
        String password = loginRequest.get("password");
        return userService.loginUser(username, password);
    }

    @GetMapping("/{id}/private-chats")
    public ResponseEntity<List<Chat>> getPrivateChatsByUserId(@PathVariable Long id) {
        List<Chat> privateChats = userService.getPrivateChatsByUserId(id);
        if (!privateChats.isEmpty()) {
            return ResponseEntity.ok(privateChats); // Возвращаем список приватных чатов
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null); // Пользователь не найден
        }
    }

    @DeleteMapping("/{userId}/private-chats/{chatId}")
    public ResponseEntity<String> deleteChat(@PathVariable Long userId, @PathVariable Long chatId) {
        boolean isDeleted = userService.deleteChat(userId, chatId);
        if (isDeleted) {
            return ResponseEntity.ok("Chat deleted successfully");
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Chat not found");
        }
    }

    public static String transliterate(String input) {
        String[][] cyrillicToLatin = {
                {"а", "a"}, {"б", "b"}, {"в", "v"}, {"г", "h"}, {"ґ", "g"}, {"д", "d"}, {"е", "e"},
                {"є", "ye"}, {"ж", "zh"}, {"з", "z"}, {"и", "y"}, {"і", "i"}, {"ї", "yi"}, {"й", "y"},
                {"к", "k"}, {"л", "l"}, {"м", "m"}, {"н", "n"}, {"о", "o"}, {"п", "p"}, {"р", "r"},
                {"с", "s"}, {"т", "t"}, {"у", "u"}, {"ф", "f"}, {"х", "kh"}, {"ц", "ts"}, {"ч", "ch"},
                {"ш", "sh"}, {"щ", "shch"}, {"ь", ""}, {"ю", "yu"}, {"я", "ya"},
                {"А", "A"}, {"Б", "B"}, {"В", "V"}, {"Г", "H"}, {"Ґ", "G"}, {"Д", "D"}, {"Е", "E"},
                {"Є", "Ye"}, {"Ж", "Zh"}, {"З", "Z"}, {"И", "Y"}, {"І", "I"}, {"Ї", "Yi"}, {"Й", "Y"},
                {"К", "K"}, {"Л", "L"}, {"М", "M"}, {"Н", "N"}, {"О", "O"}, {"П", "P"}, {"Р", "R"},
                {"С", "S"}, {"Т", "T"}, {"У", "U"}, {"Ф", "F"}, {"Х", "Kh"}, {"Ц", "Ts"}, {"Ч", "Ch"},
                {"Ш", "Sh"}, {"Щ", "Shch"}, {"Ь", ""}, {"Ю", "Yu"}, {"Я", "Ya"}
        };

        for (String[] pair : cyrillicToLatin) {
            input = input.replace(pair[0], pair[1]);
        }

        // Заменяем недопустимые символы на подчеркивание и удаляем лишние символы
        return input.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
