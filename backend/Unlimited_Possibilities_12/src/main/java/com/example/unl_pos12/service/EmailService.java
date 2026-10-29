package com.example.unl_pos12.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import org.springframework.stereotype.Service;

import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Properties;


@Service
public class EmailService {

    public void sendSimpleMessage(String to, String subject, String body, String accessToken,
                                  String fileNameResume) {
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

            // Указываем адрес отправителя
            email.setFrom(new InternetAddress("uunlimitedpossibilities@gmail.com"));
            email.addRecipient(javax.mail.Message.RecipientType.TO, new InternetAddress(to));
            email.setSubject(MimeUtility.encodeText(subject, "UTF-8", "B")); // Кодировка темы
            //email.setText(body, "UTF-8"); // Кодировка тела сообщения

            // Создание основной части сообщения
            MimeBodyPart messageBodyPart = new MimeBodyPart();
            messageBodyPart.setText(body, "UTF-8");

            // Создание части для вложения
            MimeBodyPart attachmentBodyPart = new MimeBodyPart();
            String filename = "src/main/resources/static/resumes/" + fileNameResume; // Путь к файлу
            attachmentBodyPart.attachFile(new File(filename));

            // Создание контейнера для частей сообщения
            Multipart multipart = new MimeMultipart();
            multipart.addBodyPart(messageBodyPart);
            multipart.addBodyPart(attachmentBodyPart);

            // Установка multipart в сообщение
            email.setContent(multipart);

            // Отправка сообщения
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            email.writeTo(buffer);
            byte[] bytes = buffer.toByteArray();
            String encodedEmail = Base64.getUrlEncoder().encodeToString(bytes);

            Message message = new Message();
            message.setRaw(encodedEmail);

            // Отправка сообщения
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
        }
    }
}
