package com.example.unl_pos12.model.messenger;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;

import java.time.ZonedDateTime;

@Entity
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;
    @Column(columnDefinition = "MEDIUMTEXT")
    private String encryptedContent; // Поле для E2EE
    @Column(columnDefinition = "TEXT")
    private String nonce; // Поле для E2EE
    @Column(columnDefinition = "MEDIUMTEXT")
    private String translatedContent;
    private String translationLanguage;
    private String fileUrl; // URL файла...
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "sender_id")
    private User sender;
    /*@JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private LocalDateTime timestamp;*/
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private ZonedDateTime timestamp;

    //@ManyToOne(fetch = FetchType.EAGER)
    //@JoinColumn(name = "chat_id")
    @ManyToOne
    @JoinColumn(name = "chat_id")
    @JsonBackReference
    private Chat chat;
    private Boolean delivered_status; // Статус доставки
    private Boolean read_status;      // Статус прочтения
    @Column(name = "message_type")
    private String messageType; // Поле: "text", "file", "audio"
    private String contentType; // Поле для хранения contentType
    @Column(name = "transcribed_content")
    private String transcribedContent; // Поле для транскрипции
    @Column(name = "client_message_id", nullable = true)
    private String clientMessageId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTranslatedContent() {
        return translatedContent;
    }

    public void setTranslatedContent(String translatedContent) {
        this.translatedContent = translatedContent;
    }

    public String getTranslationLanguage() {
        return translationLanguage;
    }

    public void setTranslationLanguage(String translationLanguage) {
        this.translationLanguage = translationLanguage;
    }

    public User getSender() {
        return sender;
    }

    public void setSender(User sender) {
        this.sender = sender;
    }

    /*public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }*/

    public ZonedDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(ZonedDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getFileUrl() {
        return fileUrl;
    }

    public void setFileUrl(String fileUrl) {
        this.fileUrl = fileUrl;
    }

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat;
    }

    public Boolean getDelivered_status() {
        return delivered_status;
    }

    public void setDelivered_status(Boolean delivered_status) {
        this.delivered_status = delivered_status;
    }

    public Boolean getRead_status() {
        return read_status;
    }

    public void setRead_status(Boolean read_status) {
        this.read_status = read_status;
    }

    public String getMessageType() {
        return messageType;
    }

    public void setMessageType(String messageType) {
        this.messageType = messageType;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getTranscribedContent() {
        return transcribedContent;
    }

    public void setTranscribedContent(String transcribedContent) {
        this.transcribedContent = transcribedContent;
    }

    public String getEncryptedContent() {
        return encryptedContent;
    }

    public void setEncryptedContent(String encryptedContent) {
        this.encryptedContent = encryptedContent;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public String getClientMessageId() {
        return clientMessageId;
    }

    public void setClientMessageId(String clientMessageId) {
        this.clientMessageId = clientMessageId;
    }
}
