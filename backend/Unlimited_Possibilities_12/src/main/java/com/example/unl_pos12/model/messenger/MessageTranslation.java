package com.example.unl_pos12.model.messenger;

import jakarta.persistence.*;

/**
 * Перевод сообщения на один язык.
 *
 * Раньше перевод лежал прямо в сообщении, одним полем на всех: у кого чат
 * на французском, у кого на английском — последний перевод затирал
 * предыдущий, и на новом устройстве человек видел чужой язык, а его
 * собственный перевод заказывался и оплачивался заново. Теперь у каждого
 * языка своя строка, и перевод общий для всех устройств и участников.
 */
@Entity
@Table(name = "message_translations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_message_language",
                columnNames = {"message_id", "language"}))
public class MessageTranslation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /** Код языка, как его присылает клиент: en, fr, uk… */
    @Column(name = "language", nullable = false, length = 16)
    private String language;

    @Column(name = "text", columnDefinition = "MEDIUMTEXT")
    private String text;

    public MessageTranslation() {
    }

    public MessageTranslation(Long messageId, String language, String text) {
        this.messageId = messageId;
        this.language = language;
        this.text = text;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
