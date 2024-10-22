package com.example.parsing_vacancies.controller.telegram;

import com.example.parsing_vacancies.config.telegram.BotConfig;
import com.example.parsing_vacancies.model.telegram.UserData;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TelegramBotController extends TelegramLongPollingBot {
    final BotConfig config;
    private static final Map<Long, UserData> userDataMap = new HashMap<>();

    public TelegramBotController(BotConfig config) {
        this.config = config;
    }

    @Override
    public void onUpdateReceived(Update update) {
        System.out.println("Method onUpdateReceived is working...");
        if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String messageText = update.getMessage().getText();
            System.out.println(String.format("Received message: {%s} from chatId: {%d}", messageText,
                    chatId));

            userDataMap.putIfAbsent(chatId, new UserData());

            UserData userData = userDataMap.get(chatId);

            switch (userData.getState()) {
                case WAITING_FOR_START:
                    startConversation(chatId);
                    break;
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
                case WAITING_FOR_AUTHORIZATION:
                    handleAuthorizationResponse(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_ENABLE_AI:
                    handleResumeEnableAI(chatId, messageText);
                    break;
                default:
                    startConversation(chatId);
                    break;
            }
        }
    }

    private void startConversation(long chatId) {
        userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_SITE);
        sendMessage(chatId, "Вас приветствует бот UnlimitedPossibilities12!" +
                " Я предназначен для помощи в поиске работы, все за вас сделаю," +
                " от вас только несколько минут времени для сбора информации и найдем вам работу." +
                " Потому начнем...");
        sendMessage(chatId, "С какого сайта(ов) вы хотите искать вакансии? (Work.ua или(и)" +
                " Rabota.ua)");
    }

    private void handleSiteSelection(long chatId, String messageText) {
        // Очищаем строку от лишних пробелов и знаков
        String cleanedMessage = messageText.trim()
                .replaceAll("[\\s]+", " ") // Заменяем несколько пробелов на один
                .replaceAll("(?i)([.,;:_])+\\s*", " ") // Заменяем последовательности знаков (.,;) на пробел, игнорируя регистр
                .replaceAll("\\s*(?i)([.,;:_])\\s*", " ") // Убираем пробелы вокруг знаков (.,;:), игнорируя регистр
                .replaceAll("(?i)(?<!\\w)([.,;:_])+", "") // Убираем знаки в начале строки, игнорируя регистр
                .replaceAll("(?i)(Work\\.+ua|Rabota\\.+ua)", "$1") // Оставляем строки с много точками, игнорируя регистр
                .replaceAll("(?i)(Work\\.+)(ua)", "Work.ua") // Заменяем много точек на одну, игнорируя регистр
                .replaceAll("(?i)(Rabota\\.+)(ua)", "Rabota.ua"); // Заменяем много точек на одну, игнорируя регистр

// Заменяем пробелы между Work и ua, и между Rabota и ua на точку, игнорируя регистр
        cleanedMessage = cleanedMessage.replaceAll("(?i)(Work)\\s+(ua)", "$1.ua")
                .replaceAll("(?i)(Rabota)\\s+(ua)", "$1.ua").trim();

// Заменяем множественные пробелы между Work.ua и Rabota.ua на один пробел, игнорируя регистр
        cleanedMessage = cleanedMessage.replaceAll("(?i)(Work\\.ua)\\s+(Rabota\\.ua)", "$1 $2");

// Добавляем точки, если они отсутствуют, игнорируя регистр
        cleanedMessage = cleanedMessage.replaceAll("(?i)(Work)(ua)", "Work.ua")
                .replaceAll("(?i)(Rabota)(ua)", "Rabota.ua");

        // Проверяем наличие "Work.ua" и "Rabota.ua"
        boolean hasWork = cleanedMessage.toLowerCase().contains("work.ua");
        boolean hasRabota = cleanedMessage.toLowerCase().contains("rabota.ua");

        if (hasWork || hasRabota) {
            // Сохраняем данные
            userDataMap.get(chatId).setSite(cleanedMessage);
            userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_POSITION);
            sendMessage(chatId, "Какую должность вы ищете?");
        } else {
            sendMessage(chatId, "Вы ввели некорректные данные. Пожалуйста, напишите 'Work.ua' или(и) 'Rabota.ua'.");
        }
    }

    private void handlePositionSelection(long chatId, String messageText) {
        userDataMap.get(chatId).setPosition(messageText);
        userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_CITY);
        sendMessage(chatId, "Введите один из городов поиска вакансий: Киев, Харьков, Одесса, Днепр.");
    }

    private void handleCitySelection(long chatId, String messageText) {
        // Очищаем строку от лишних пробелов и знаков препинания
        String cleanedMessage = messageText.trim()
                .replaceAll("[\\s,;:_]+", " ") // Заменяем пробелы и знаки на пробел
                .replaceAll("[^\\w\\u0400-\\u04FF]", "") // Удаляем все, кроме букв и цифр
                .replaceAll("[\\s]+", " "); // Удаляем лишние пробелы

        // Список допустимых городов на украинском и английском
        List<String> validCities = Arrays.asList(
                "Киев", "Харьков", "Одесса", "Днепр",
                "Київ", "Харків", "Одеса", "Дніпро",
                "Kiev", "Kyiv", "Kharkiv", "Odesa", "Dnipro"
        );

        String[] words = cleanedMessage.split(" ");
        if (words.length != 1) {
            sendMessage(chatId, "Пожалуйста, введите только один город.");
            return;
        }

        // Проверка, соответствует ли введенный город одному из допустимых
        if (validCities.stream().anyMatch(city -> city.equalsIgnoreCase(cleanedMessage))) {
            userDataMap.get(chatId).setCity(cleanedMessage);
            userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_COUNT);
            sendMessage(chatId, "Сколько вакансий вы хотите получить?");
        } else {
            sendMessage(chatId, "Вы ввели некорректные данные. Пожалуйста, введите один из предложенных городов.");
        }
    }

    private void handleCountSelection(long chatId, String messageText) {
        try {
            int countVacancies = Integer.parseInt(messageText);
            userDataMap.get(chatId).setCountVacancies(countVacancies);
            userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_AUTHORIZATION);
            sendMessage(chatId, "Теперь вам необходимо авторизироваться через Google. При согласии введите 'Да' или 'Нет' в случае отказа.");
            //sendMessage(chatId, "Ваш запрос собран: " + userDataMap.get(chatId));
            //userDataMap.remove(chatId); // Удаляем данные после завершения
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Пожалуйста, введите число.");
        }
    }

    private void handleAuthorizationResponse(long chatId, String messageText) {
        // Очищаем строку от лишних пробелов и знаков препинания
        String cleanedMessage = messageText.trim()
                .replaceAll("[\\s,;:_]+", " ") // Заменяем пробелы и знаки на пробел
                .replaceAll("[^\\w\\u0400-\\u04FF]", "") // Удаляем все, кроме букв и цифр
                .replaceAll("[\\s]+", " "); // Удаляем лишние пробелы

        // Список допустимых ответов
        List<String> validResponses = Arrays.asList(
                "да", "нет", "да", "нет", // на русском
                "yes", "no", // на английском
                "так", "ні" // на украинском
        );

        String[] words = cleanedMessage.split(" ");
        if (words.length != 1) {
            sendMessage(chatId, "Пожалуйста, введите что то одно.");
            return;
        }

        // Проверяем, соответствует ли введенный ответ одному из допустимых
        if (validResponses.stream().anyMatch(response -> response.equalsIgnoreCase(cleanedMessage))) {
            if (cleanedMessage.equalsIgnoreCase("да") || cleanedMessage.equalsIgnoreCase("yes") || cleanedMessage.equalsIgnoreCase("так")) {
                // Логика для авторизации через Google
                sendMessage(chatId, "Вы выбрали авторизацию через Google.");
                startAutorization(chatId);
            } else {
                sendMessage(chatId, "Вы отказались от авторизации.");
                finishSession(chatId);
                userDataMap.remove(chatId); // Удаление данных после завершения
            }
        } else {
            sendMessage(chatId, "Вы ввели некорректные данные. Пожалуйста, введите 'Да', 'Нет', 'Yes', 'No', 'Так' или 'Ні'.");
        }
    }

    private void startAutorization(long chatId) {
        //userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_SITE);
        String authUrl = "https://unlimitedpossibilities12.org/login?chatId=" + chatId;
        sendMessage(chatId, "Перейдите по предложенной ссылке для авторизации: \n" + authUrl);
    }

    private void handleResumeEnableAI(long chatId, String messageText){
        // Очищаем строку от лишних пробелов и знаков препинания
        String cleanedMessage = messageText.trim()
                .replaceAll("[\\s,;:_]+", " ") // Заменяем пробелы и знаки на пробел
                .replaceAll("[^\\w\\u0400-\\u04FF]", "") // Удаляем все, кроме букв и цифр
                .replaceAll("[\\s]+", " "); // Удаляем лишние пробелы

        // Список допустимых ответов
        List<String> validResponses = Arrays.asList(
                "да", "нет", "да", "нет", // на русском
                "yes", "no", // на английском
                "так", "ні" // на украинском
        );

        String[] words = cleanedMessage.split(" ");//
        if (words.length != 1) {
            sendMessage(chatId, "Пожалуйста, введите что то одно.");
            return;
        }

        // Проверяем, соответствует ли введенный ответ одному из допустимых
        if (validResponses.stream().anyMatch(response -> response.equalsIgnoreCase(cleanedMessage))) {
            if (cleanedMessage.equalsIgnoreCase("да") || cleanedMessage.equalsIgnoreCase("yes") || cleanedMessage.equalsIgnoreCase("так")) {
                userDataMap.get(chatId).setEnableAI(true);
                userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_FULLNAME);
                sendMessage(chatId, "Отличный выбор! Благодаря участию ИИ ваше резюме будет бесподобно и полноценно!");
            } else {
                userDataMap.get(chatId).setEnableAI(true);
                userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_FULLNAME);
                sendMessage(chatId, "Вы отказались от участия ИИ. Ничего, разберемся и без него!");
            }
        } else {
            sendMessage(chatId, "Вы ввели некорректные данные. Пожалуйста, введите 'Да', 'Нет', 'Yes', 'No', 'Так' или 'Ні'.");
        }
    }

    public void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void finishSession(long chatId) {
        sendMessage(chatId, "Прощайте, спасибо за использование!");

        // Здесь можно добавить логику для отключения пользователя, если требуется
        // Например, можно записать это в базу данных или журнал
    }

    @Override
    public String getBotUsername() {
        return config.getBotName();
    }
    @Override
    public String getBotToken() {
        return config.getToken();
    }

    public Map<Long, UserData> getUserDataMap() {
        return userDataMap;
    }
}
