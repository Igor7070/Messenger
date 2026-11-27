package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.model.messenger.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findBySender(User sender);
    List<Message> findByContentAndSenderIdAndChatId(String content, Long senderId, Long chatId);
    void deleteBySenderId(Long senderId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Message m WHERE m.chat.id = :chatId")
    void deleteByChatId(@Param("chatId") Long chatId);

    // Добавлено: Метод для поиска сообщений по chatId
    List<Message> findByChatId(Long chatId);

    @Query("SELECT m FROM Message m WHERE m.fileUrl = :fileUrl")
    Message findByFileUrl(String fileUrl);

    /*
     * Расшифровку и перевод вписываем одной колонкой.
     *
     * Прежде сообщение читали в начале запроса, а сохраняли целиком после
     * разбора речи — а он длится секунды. За это время собеседник успевал
     * прочитать запись, и сохранение возвращало «не прочитано»: вторая
     * галочка гасла сама собой, стоило выйти из переписки и вернуться.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Message m SET m.transcribedContent = :текст WHERE m.id = :id")
    void вписатьРасшифровку(@Param("id") Long id, @Param("текст") String текст);

    @Modifying
    @Transactional
    @Query("UPDATE Message m SET m.translatedContent = :текст, m.translationLanguage = :язык WHERE m.id = :id")
    void вписатьПеревод(@Param("id") Long id, @Param("текст") String текст, @Param("язык") String язык);
}
