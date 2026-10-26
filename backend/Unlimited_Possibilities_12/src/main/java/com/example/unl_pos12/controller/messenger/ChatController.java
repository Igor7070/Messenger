package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.service.ChatService;
import com.example.unl_pos12.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chats")
public class ChatController {
    @Autowired
    private ChatService chatService;
    @Autowired
    private com.example.unl_pos12.service.AuthzService authz;
    @Autowired
    private UserService userService;
    @Autowired
    private com.example.unl_pos12.service.ChatMemberService chatMembers;

    @PostMapping
    public ResponseEntity<?> createChat(@RequestBody Chat chat,
                                        jakarta.servlet.http.HttpServletRequest request) {
        System.out.println("Creating chat: " + chat.getName() + ", isPrivate: " + chat.isPrivate());
        // Создателя берём из токена, а не из тела запроса: иначе его можно подменить
        Long creator = authz.currentUserId(request);
        if (creator != null && chat.getCreatedBy() == null) chat.setCreatedBy(creator);
        // Новая группа закрыта, если клиент явно не попросил обратного
        boolean group = !chat.isPrivate() && !Boolean.TRUE.equals(chat.getIsSecret());
        if (group && chat.getIsPublic() == null) chat.setIsPublic(false);

        try {
            // Создаем чат
            Chat createdChat = chatService.createChat(chat);

            // Создатель группы сразу становится её владельцем и первым участником:
            // иначе у новой закрытой группы не было бы ни одного человека внутри
            if (group && creator != null) {
                chatMembers.addOwner(createdChat.getId(), creator);
            }

            // Если чат приватный, добавляем его обоим пользователям
            if (chat.isPrivate()) {
                String[] usernames = chat.getName().split("_"); // Предполагаем, что имя чата состоит из "Имя1_Имя2"
                if (usernames.length == 2) {
                    User user1 = userService.getUserByUsername(usernames[0]);
                    User user2 = userService.getUserByUsername(usernames[1]);

                    // Добавляем чат в список приватных чатов пользователей.
                    // createChat может вернуть уже существующий чат — не дублируем связь.
                    if (user1.getPrivateChats().stream().noneMatch(c -> c.getId().equals(createdChat.getId()))) {
                        user1.getPrivateChats().add(createdChat);
                    }
                    if (user2.getPrivateChats().stream().noneMatch(c -> c.getId().equals(createdChat.getId()))) {
                        user2.getPrivateChats().add(createdChat);
                    }

                    // Обновляем пользователей
                    userService.updateUser(user1);
                    userService.updateUser(user2);
                } else {
                    System.out.println("Invalid chat name format: " + chat.getName());
                }
            }

            System.out.println("Chat " + chat.getName() + " created.");
            System.out.println("ChatId = " + createdChat.getId());
            return ResponseEntity.ok(createdChat);
        } catch (RuntimeException e) {
            // Обработка ошибки, когда чат с таким именем уже существует
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        }
    }

    /**
     * Список групповых чатов: публичные видны всем, закрытые — только своим.
     * До входа (токена нет) отдаём лишь публичные: с этого экрана логинятся.
     */
    @GetMapping
    public List<Chat> getAllChats(jakarta.servlet.http.HttpServletRequest request) {
        Long me = authz.currentUserId(request);
        return chatService.getAllChats().stream()
                .filter(chat -> chatMembers.isPublicGroup(chat)
                        || (me != null && chatMembers.isMember(chat.getId(), me)))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Настройки группы: имя — админам, публичность — только владельцу.
     *
     * Переименования не было вовсе, а закрыть или открыть группу после создания
     * было нечем: признак задавался один раз и навсегда.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateChat(@PathVariable Long id,
                                        @RequestBody Map<String, Object> body,
                                        jakarta.servlet.http.HttpServletRequest request) {
        Chat chat = chatService.getChatById(id);
        if (chat == null) return ResponseEntity.notFound().build();
        if (chat.isPrivate() || Boolean.TRUE.equals(chat.getIsSecret())) {
            return ResponseEntity.badRequest().body(Map.of("error", "This is a one-to-one chat"));
        }

        if (body.containsKey("name")) {
            authz.requireChatManager(request, id);
            String name = String.valueOf(body.get("name")).trim();
            if (name.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Name must not be empty"));
            }
            chat.setName(name);
        }

        if (body.containsKey("isPublic")) {
            Long me = authz.currentUserId(request);
            // Открыть группу всем или спрятать — решение владельца, не админа
            if (me != null && !chatMembers.isOwner(id, me) && !me.equals(chat.getCreatedBy())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "Only the owner can change this"));
            }
            chat.setIsPublic(Boolean.valueOf(String.valueOf(body.get("isPublic"))));
        }

        return ResponseEntity.ok(chatService.saveChat(chat));
    }

    /**
     * Картинка группы. Меняют админы и владелец — то же правило, что для имени.
     *
     * Кладём рядом с аватарами пользователей и отдаём тем же обработчиком
     * статики (/avatars/**), чтобы не заводить второе хранилище. Имя файла
     * строим по номеру чата: так новая картинка заменяет старую, а не копит
     * мусор при каждой замене.
     */
    @PostMapping("/{id}/avatar")
    public ResponseEntity<?> uploadChatAvatar(@PathVariable Long id,
                                              @RequestParam("avatar") org.springframework.web.multipart.MultipartFile avatar,
                                              jakarta.servlet.http.HttpServletRequest request) {
        authz.requireChatManager(request, id);
        Chat chat = chatService.getChatById(id);
        if (chat == null) return ResponseEntity.notFound().build();
        if (chat.isPrivate() || Boolean.TRUE.equals(chat.getIsSecret())) {
            return ResponseEntity.badRequest().body(Map.of("error", "This is a one-to-one chat"));
        }
        if (avatar == null || avatar.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file"));
        }

        try {
            java.io.File directory = new java.io.File("src/main/resources/static/avatars/").getAbsoluteFile();
            if (!directory.exists() && !directory.mkdirs()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Cannot create the avatars folder"));
            }
            String original = avatar.getOriginalFilename();
            String extension = (original != null && original.contains("."))
                    ? original.substring(original.lastIndexOf('.')).toLowerCase()
                    : ".png";
            // Расширение берём из имени файла, но только знакомое: иначе в папке
            // со статикой мог бы оказаться файл с любым расширением
            if (!extension.matches("\\.(png|jpg|jpeg|webp|gif)")) extension = ".png";
            String fileName = "group_" + id + extension;
            avatar.transferTo(new java.io.File(directory, fileName));
            chat.setAvatar("/avatars/" + fileName);
            Chat saved = chatService.saveChat(chat);
            System.out.println("Group avatar saved: " + fileName);
            return ResponseEntity.ok(saved);
        } catch (java.io.IOException e) {
            System.out.println("Error saving group avatar: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not save the picture"));
        }
    }

    @DeleteMapping("/{id}")
    public void deleteChat(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        authz.requireChatOwner(request, id); // групповой чат удаляет только создатель
        chatService.deleteChat(id);
        chatMembers.forgetChat(id); // участники и приглашения вместе с чатом
    }

    @GetMapping("/{id}/messages")
    public List<Message> getMessagesByChatId(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        authz.requireChatAccess(request, id); // историю приватного/секретного чата — только участнику
        return chatService.getMessagesByChatId(id);
    }

    @GetMapping("/{id}")
    public Chat getChatById(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        authz.requireChatAccess(request, id);
        return chatService.getChatById(id);
    }

    /**
     * Кто состоит в приватном или секретном чате.
     *
     * Клиент раньше вычислял собеседника разбором имени чата («А_Б», у
     * секретного — «А_Б_secret»). Имя пользователя само может содержать «_»,
     * и тогда разбор давал несуществующее имя: запрос такого пользователя
     * возвращал 404, переписка не открывалась и выбрасывала на главную.
     * Здесь участники берутся по связи, имя чата не разбирается вовсе.
     */
    @GetMapping("/{id}/participants")
    public List<User> getParticipants(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        authz.requireChatAccess(request, id);
        return userService.getMembersOfChat(id);
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkChatExists(@RequestParam String name1, @RequestParam String name2) {
        Long chatId = chatService.findChatByUsers(name1, name2);
        Map<String, Object> response = new HashMap<>();
        response.put("chatId", chatId != null ? chatId : -1); // Возвращаем -1, если чат не найден
        return ResponseEntity.ok(response);
    }

    @GetMapping("/lastChatId")
    public ResponseEntity<Long> getLastChatId() {
        Long lastChatId = chatService.getLastChatId();
        return ResponseEntity.ok(lastChatId);
    }
}
