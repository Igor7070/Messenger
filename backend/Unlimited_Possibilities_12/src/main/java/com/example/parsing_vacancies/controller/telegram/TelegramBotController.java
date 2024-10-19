package com.example.parsing_vacancies.controller.telegram;

import com.example.parsing_vacancies.model.telegram.UserData;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

@Component
public class TelegramBotController extends TelegramLongPollingBot {

    private static final Logger logger = (Logger) LoggerFactory.getLogger(TelegramBotController.class);
    private final String botUsername;
    private final String botToken;
    private final Map<Long, UserData> userDataMap = new HashMap<>();

    public TelegramBotController(@Value("${telegrambots.username}") String botUsername,
                                 @Value("${telegrambots.api.token}") String botToken) {
        this.botUsername = botUsername;
        this.botToken = botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String messageText = update.getMessage().getText();
            logger.info(String.format("Received message: {%s} from chatId: {%d}", messageText,
                    chatId));

            userDataMap.putIfAbsent(chatId, new UserData());

            UserData userData = userDataMap.get(chatId);

            switch (userData.getState()) {
                case WAITING_FOR_SITE:
                    handleSiteSelection(chatId, messageText);
                    break;
                case WAITING_FOR_POSITION:
                    handlePositionSelection(chatId, messageText);
                    break;
                case WAITING_FOR_CITY:
                    handleCitySelection(chatId, messageText);
                    break;
                case WAITING_FOR_COUNT:
                    handleCountSelection(chatId, messageText);
                    break;
                default:
                    startConversation(chatId);
                    break;
            }
        }
    }

    private void startConversation(long chatId) {
        userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_SITE);
        sendMessage(chatId, "Привет! С какого сайта вы хотите искать вакансии? (Work.ua или Rabota.ua)");
    }

    private void handleSiteSelection(long chatId, String messageText) {
        if (messageText.equalsIgnoreCase("Work.ua") || messageText.equalsIgnoreCase("Rabota.ua")) {
            userDataMap.get(chatId).setSite(messageText);
            userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_POSITION);
            sendMessage(chatId, "Какую должность вы ищете?");
        } else {
            sendMessage(chatId, "Пожалуйста, напишите 'Work.ua' или 'Rabota.ua'");
        }
    }

    private void handlePositionSelection(long chatId, String messageText) {
        userDataMap.get(chatId).setPosition(messageText);
        userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_CITY);
        sendMessage(chatId, "В каком городе вы хотите искать вакансии?");
    }

    private void handleCitySelection(long chatId, String messageText) {
        userDataMap.get(chatId).setCity(messageText);
        userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_COUNT);
        sendMessage(chatId, "Сколько вакансий вы хотите получить?");
    }

    private void handleCountSelection(long chatId, String messageText) {
        try {
            int countVacancies = Integer.parseInt(messageText);
            userDataMap.get(chatId).setCountVacancies(countVacancies);
            sendMessage(chatId, "Ваш запрос собран: " + userDataMap.get(chatId));
            userDataMap.remove(chatId); // Удаляем данные после завершения
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Пожалуйста, введите число.");
        }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }
}
