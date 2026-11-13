package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.MessageTranslation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MessageTranslationRepository extends JpaRepository<MessageTranslation, Long> {

    Optional<MessageTranslation> findByMessageIdAndLanguage(Long messageId, String language);

    /** Переводы целой переписки на один язык — одним запросом. */
    List<MessageTranslation> findByMessageIdInAndLanguage(Collection<Long> messageIds, String language);

    @Modifying
    @Transactional
    @Query("DELETE FROM MessageTranslation t WHERE t.messageId = :messageId")
    void deleteByMessageId(@Param("messageId") Long messageId);

    @Modifying
    @Transactional
    @Query("DELETE FROM MessageTranslation t WHERE t.messageId IN " +
            "(SELECT m.id FROM Message m WHERE m.chat.id = :chatId)")
    void deleteByChatId(@Param("chatId") Long chatId);
}
