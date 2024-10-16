package com.example.parsing_vacancies.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.stereotype.Service;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Properties;


@Service
public class EmailService {
    @Autowired
    private OAuth2AuthorizedClientService authorizedClientService;

    public void sendSimpleMessage(String to, String subject, String body, String accessToken) {
        try {
            // Создание HttpRequestInitializer с использованием accessToken
            HttpRequestInitializer requestInitializer = httpRequest -> {
                httpRequest.getHeaders().setAuthorization("Bearer " + accessToken);
            };

            // Создание Gmail API клиента с использованием токена доступа
            Gmail service = new Gmail.Builder(GoogleNetHttpTransport.newTrustedTransport(), JacksonFactory.getDefaultInstance(), requestInitializer)
                    .setApplicationName("Unlimited Possibilities 12")
                    .build();

            // Создание сообщения
            Properties props = new Properties();
            Session session = Session.getInstance(props, null);
            MimeMessage email = new MimeMessage(session);
            email.setFrom(new InternetAddress("your_email@gmail.com"));  // Укажите свой email
            email.addRecipient(javax.mail.Message.RecipientType.TO, new InternetAddress(to));
            email.setSubject(subject);
            email.setText(body);

            // Отправка сообщения
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            email.writeTo(buffer);
            byte[] bytes = buffer.toByteArray();
            String encodedEmail = Base64.getUrlEncoder().encodeToString(bytes);
            Message message = new Message();
            message.setRaw(encodedEmail);
            service.users().messages().send("me", message).execute();
        } catch (GeneralSecurityException e) {
            e.printStackTrace();
            throw new RuntimeException("Security exception: " + e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("IO exception: " + e.getMessage());
        } catch (MessagingException e) {
            e.printStackTrace();
            throw new RuntimeException("Messaging exception: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
