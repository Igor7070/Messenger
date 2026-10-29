package com.example.unl_pos12.service;

import com.example.unl_pos12.controller.handler.InvalidCredentialsException;
import com.example.unl_pos12.controller.handler.UsernameTakenException;
import com.example.unl_pos12.model.messenger.Chat;
import com.example.unl_pos12.model.messenger.ChatMember;
import com.example.unl_pos12.model.messenger.User;
import com.example.unl_pos12.repo.CallRepository;
import com.example.unl_pos12.repo.ChatMemberRepository;
import com.example.unl_pos12.repo.ChatRepository;
import com.example.unl_pos12.repo.MessageRepository;
import com.example.unl_pos12.repo.PublicKeyHistoryRepository;
import com.example.unl_pos12.repo.PublicKeyRepository;
import com.example.unl_pos12.repo.PushTokenRepository;
import com.example.unl_pos12.repo.SignKeyRepository;
import com.example.unl_pos12.repo.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class UserService {
    /**
     * Пароли хранятся как BCrypt-хеши. Записи, сделанные до перехода на
     * хеширование, лежат открытым текстом; они перехешируются при первом
     * успешном логине (пароль в этот момент известен), для пользователя
     * это незаметно.
     */
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private static boolean isBcryptHash(String stored) {
        return stored != null && stored.length() == 60
                && (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$"));
    }

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ChatRepository chatRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ChatMemberRepository chatMemberRepository;
    @Autowired
    private ChatMemberService chatMemberService;
    @Autowired
    private PublicKeyRepository publicKeyRepository;
    @Autowired
    private PublicKeyHistoryRepository publicKeyHistoryRepository;
    @Autowired
    private SignKeyRepository signKeyRepository;
    @Autowired
    private PushTokenRepository pushTokenRepository;
    @Autowired
    private CallRepository callRepository;

    public User createUser(User user) {
        String errorMessage = "";
        String username = user.getUsername() == null ? "" : user.getUsername().trim();
        if (username.isEmpty()) {
            throw new RuntimeException("Username is required");
        }
        user.setUsername(username);
        // Проверка на существование пользователя
        if (userRepository.findByUsername(username).isPresent()) {
            errorMessage = "This username is already taken";
            System.out.println(errorMessage);
            throw new UsernameTakenException(errorMessage);
        }
        // Проверка на валидность пароля
        if (!isValidPassword(user.getPassword())) {
            errorMessage = "Password must be at least 9 characters long, contain letters and numbers";
            System.out.println(errorMessage);
            throw new RuntimeException(errorMessage);
        }
        user.setPassword(PASSWORD_ENCODER.encode(user.getPassword()));
        try {
            return userRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Та же регистрация пришла дважды почти одновременно: проверка выше
            // успела отработать у обеих, а уникальность имени держит база.
            // Вторая попытка получает отказ здесь, и пары учётных записей с
            // одним именем больше не появляется.
            System.out.println("Username taken (race on insert): " + username);
            throw new UsernameTakenException("This username is already taken");
        }
    }

    public User loginUser(String username, String password) {
        String errorMessage = "";
        Optional<User> user = userRepository.findByUsername(username);
        if (user.isPresent() && password != null) {
            User u = user.get();
            String stored = u.getPassword();
            boolean ok;
            if (isBcryptHash(stored)) {
                ok = PASSWORD_ENCODER.matches(password, stored);
            } else {
                // Запись до перехода на хеширование — открытый текст
                ok = password.equals(stored);
                if (ok) {
                    u.setPassword(PASSWORD_ENCODER.encode(password));
                    userRepository.save(u);
                    System.out.println("Password re-hashed on login for user: " + username);
                }
            }
            if (ok) return u;
        }
        {
            errorMessage = "Invalid username or password";
            System.out.println("Login refused: " + username);
            throw new InvalidCredentialsException(errorMessage);
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

    public String getUsername(Long userId) {
        try {
            return getUserById(userId).getUsername();
        } catch (Exception e) {
            return "Unknown";
        }
    }

    public User updateUser(User user) {
        return userRepository.save(user);
    }

    private boolean isValidPassword(String password) {
        return password.length() >= 9 && password.matches(".*[a-zA-Z].*") && password.matches(".*\\d.*");
    }

    /**
     * Участники приватного или секретного чата — по связи, а не по имени.
     *
     * Имя такого чата складывается из имён собеседников через «_», и клиент
     * вычислял собеседника разбором этой строки. Имя пользователя само может
     * содержать «_», и тогда разбор давал несуществующее имя: переписка не
     * открывалась вовсе.
     */
    public List<User> getMembersOfChat(Long chatId) {
        return userRepository.findMembersOfChat(chatId);
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

    @Transactional
    public boolean removeChatIfNotExists(Long chatId) {
        // Получаем чат по ID
        Chat chat = chatRepository.findById(chatId).orElse(null);
        if (chat == null) {
            return false; // Чат не найден
        }

        // Раньше участники вычислялись разбором имени чата через split("_")
        // с требованием ровно двух частей. Имя секретного чата — «A_B_secret»,
        // три части, поэтому метод бросал IllegalArgumentException, клиент
        // получал 500, и удалённый обоими секретный чат навсегда оставался в базе.
        // Считаем владельцев по связи user <-> chat, имя тут вообще не нужно.
        long owners = userRepository.countOwnersOfChat(chatId);
        if (owners > 0) {
            return false; // Чат существует у хотя бы одного пользователя
        }

        // Сначала удаляем все сообщения, связанные с чатом
        messageRepository.deleteByChatId(chatId);
        // Если чат не найден у ни одного пользователя, удаляем его из базы
        chatRepository.deleteById(chatId); // Удаляем чат по ID
        return true; // Чат успешно удален из базы
    }

    public boolean chatExistsForUser(Long userId, Long chatId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user != null) {
            return user.getPrivateChats().stream()
                    .anyMatch(chat -> chat.getId().equals(chatId));
        }
        return false; // Пользователь не найден
    }

    public boolean addChatToUser(Long userId, Long chatId) {
        User user = userRepository.findById(userId).orElse(null);
        Chat chat = chatRepository.findById(chatId).orElse(null);

        if (user != null && chat != null) {
            if (!user.getPrivateChats().contains(chat)) { // Проверяем, что чат еще не добавлен
                user.getPrivateChats().add(chat);
                userRepository.save(user); // Сохраняем обновленного пользователя
            }
            // Уже в списке — это тоже успех. Раньше здесь возвращался false,
            // клиент получал 404 и прерывал открытие чата на шаге «добавить
            // собеседника», если тот свой экземпляр чата не удалял.
            return true;
        }
        return false; // Пользователь или чат не найден
    }

    /**
     * Удаление учётки вместе со всем, что к ней привязано.
     *
     * Раньше чистились только сообщения, а ключи шифрования, их история, метки
     * для уведомлений и членство в группах оставались в базе навсегда: после
     * каждой удалённой учётки копился мусор. Журнал звонков вдобавок связан с
     * пользователем внешним ключом — у того, кто звонил, удаление вообще не
     * прошло бы.
     */
    @Transactional // Эта аннотация позволяет выполнять операции в транзакции
    public boolean deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            return false;
        }

        // Личные и секретные чаты держатся на связи user <-> chat, а не на
        // списке участников: запоминаем их до удаления, чтобы потом проверить,
        // остался ли у переписки хоть один хозяин
        List<Long> personalChats = new java.util.ArrayList<>();
        User leaving = userRepository.findById(id).orElse(null);
        if (leaving != null) {
            for (Chat chat : leaving.getPrivateChats()) {
                personalChats.add(chat.getId());
            }
        }

        // Сначала удаляем все сообщения, отправленные пользователем
        messageRepository.deleteBySenderId(id);

        // Из групп выходим по-человечески: если уходит владелец, права
        // переходят к админу или самому давнему участнику — иначе группа
        // осталась бы без хозяина, и её некому было бы ни настроить, ни удалить
        for (ChatMember membership : chatMemberRepository.findByUserId(id)) {
            Long chatId = membership.getChatId();
            chatMemberService.leave(chatId, id);
            // Группа, из которой ушёл последний участник, никому не видна и
            // никому не принадлежит — держать её в базе незачем
            if (chatMemberRepository.countByChatId(chatId) == 0) {
                messageRepository.deleteByChatId(chatId);
                chatRepository.deleteById(chatId);
            }
        }

        callRepository.deleteAll(callRepository.findByUserId(id));
        publicKeyRepository.deleteByUserId(id);
        publicKeyHistoryRepository.deleteByUserId(id);
        signKeyRepository.deleteByUserId(id);
        pushTokenRepository.deleteByUserId(id);

        userRepository.deleteById(id);
        userRepository.flush();

        // Переписка, которую не держит больше никто, никому не видна —
        // раньше такие оставались в базе навсегда
        for (Long chatId : personalChats) {
            if (userRepository.countOwnersOfChat(chatId) == 0) {
                messageRepository.deleteByChatId(chatId);
                chatRepository.deleteById(chatId);
            }
        }
        return true;
    }

    @Transactional
    public void setUserOnline(Long userId, boolean online) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setOnline(online);
        if (online) {
            user.setLastHeartbeat(System.currentTimeMillis()); // Обновляем метку при установке онлайн
        } else {
            user.setLastHeartbeat(null); // Сбрасываем метку при оффлайн
        }
        userRepository.save(user);
    }

    @Transactional
    public void updateHeartbeat(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        user.setLastHeartbeat(System.currentTimeMillis());
        userRepository.save(user);
    }

    public boolean isUserOnline(Long userId) {
        return userRepository.findById(userId)
                .map(User::isOnline)
                .orElse(false);
    }

    public List<User> getOnlineUsers() {
        return userRepository.findByOnline(true);
    }
}
