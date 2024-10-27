package com.example.parsing_vacancies.controller.telegram;

import com.example.parsing_vacancies.config.telegram.BotConfig;
import com.example.parsing_vacancies.model.Vacancy;
import com.example.parsing_vacancies.model.resume.Education;
import com.example.parsing_vacancies.model.resume.Resume;
import com.example.parsing_vacancies.model.resume.WorkExperience;
import com.example.parsing_vacancies.model.telegram.ProfanityFilter;
import com.example.parsing_vacancies.model.telegram.UserData;
import com.example.parsing_vacancies.service.OpenAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.util.*;

@Component
public class TelegramBotController extends TelegramLongPollingBot {
    @Autowired
    private TelegramCreateResume telegramCreateResume; // Инъекция
    @Autowired
    private TelegramSendingResumeAndReport telegramSendingResumeAndReport;
    @Autowired
    private OpenAIService openAIService;
    final BotConfig config;
    private static final Map<Long, UserData> userDataMap = new HashMap<>();

    public TelegramBotController(BotConfig config) {
        this.config = config;
    }

    @Override
    public void onUpdateReceived(Update update) {
        System.out.println("Method onUpdateReceived is working...");
        if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
        } else if (update.hasMessage() && update.getMessage().hasText()) {
            long chatId = update.getMessage().getChatId();
            String messageText = update.getMessage().getText();
            System.out.println(String.format("Received message: {%s} from chatId: {%d}", messageText,
                    chatId));

            userDataMap.putIfAbsent(chatId, new UserData());

            UserData userData = userDataMap.get(chatId);

            if ("/start".equals(messageText)) {
                sendWelcomeMessage(chatId);
                return;
            }

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
                case WAITING_FOR_RESUME_FULLNAME:
                    handleResumeFullName(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_EMAIL:
                    handleResumeEmail(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_PHONE:
                    handleResumePhone(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_CITY:
                    handleResumeCity(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_PURPOSE_JOB_SEARCH:
                    handleResumePurposeJobSearch(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_EDUCATION_QUANTITY:
                    handleResumeEducationQuantity(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_EDUCATION_NAME:
                    handleResumeEducationName(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_EDUCATION_SPECIALITY:
                    handleResumeEducationSpeciality(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_EDUCATION_YEARS:
                    handleResumeEducationYears(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_EXPERIENCE_QUANTITY:
                    handleResumeExperienceQuantity(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_EXPERIENCE_NAME:
                    handleResumeExperienceName(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_EXPERIENCE_POSITION:
                    handleResumeExperiencePosition(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_EXPERIENCE_YEARS:
                    handleResumeExperienceYears(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_LANGUAGES:
                    handleResumeLanguages(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_SKILLS_AND_ABILITIES:
                    handleResumeSkillsAndAbilities(chatId, messageText);
                    break;
                case WAITING_FOR_RESUME_ACHIEVEMENTS:
                    handleResumeAchievements(chatId, messageText);
                    break;
                case WAITING_CHOICE_METHOD:
                    handleChoiceMethod(chatId, messageText);
                    break;
                case WAITING_SEND_RESUME_TO_ALL_VACANCIES:
                    handleSendResumeToAllVacancies(chatId, messageText);
                    break;
                case WAITING_ID_VACANCY:
                    handleIdVacancy(chatId, messageText);
                    break;
                case WAITING_SEND_RESUME:
                    handleSendResume(chatId, messageText);
                    break;
                case WAITING_RESULT_SENDING_RESUME:
                    handleResultChattingWithTheBot(chatId, messageText);
                    break;
                case WAITING_APOLOGY:
                    handleApology(chatId, messageText);
                    break;
                default:
                    startConversation(chatId);
                    break;
            }
        }
    }

    private void sendWelcomeMessage(long chatId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();

        InlineKeyboardButton searchJobButton = new InlineKeyboardButton();
        searchJobButton.setText("Поиск работы");
        searchJobButton.setCallbackData("start_job_search"); // Идентификатор для обработки

        List<InlineKeyboardButton> row = new ArrayList<>();
        row.add(searchJobButton);
        buttons.add(row);

        markup.setKeyboard(buttons);

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Вас приветствует бот UnlimitedPossibilities12! Выберите кнопку ниже для выполнения интересующей вас функции.");
        message.setReplyMarkup(markup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        System.out.println("Method handleCallbackQuery is working...");
        long chatId = callbackQuery.getMessage().getChatId();
        String callbackData = callbackQuery.getData();

        if ("start_job_search".equals(callbackData)) {
            // Начинаем диалог
            System.out.println("The job search button has been pressed");
            startConversation(chatId);
            answerCallbackQuery(callbackQuery.getId(), "Вы начали поиск работы.");
        }
    }

    private void answerCallbackQuery(String callbackQueryId, String text) {
        System.out.println("Method answerCallbackQuery is working...");
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackQueryId);
        answer.setText(text);
        answer.setShowAlert(false);

        try {
            execute(answer);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void startConversation(long chatId) {
        userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_SITE);
        sendMessage(chatId, "Я помогу вам в поиске работы, все за вас сделаю," +
                " от вас нужно только несколько минут времени для сбора информации и найдем вам работу." +
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
            sendMessage(chatId, "Сколько вакансий вы хотите получить? Если при выборе поиска сайта " +
                    "вы указали два сайта, вы получите данное количество вакансий с каждого сайта.");
        } else {
            sendMessage(chatId, "Вы ввели некорректные данные. Пожалуйста, введите один из предложенных городов.");
        }
    }

    private void handleCountSelection(long chatId, String messageText) {
        try {
            int countVacancies = Integer.parseInt(messageText.trim());
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
                "да", "нет", // на русском
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
                "да", "нет", // на русском
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
                userDataMap.get(chatId).setEnableAI(true);
                userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_FULLNAME);
                sendMessage(chatId, "Отличный выбор! Благодаря участию ИИ ваше резюме будет бесподобно и полноценно!");
                sendMessage(chatId, "Теперь нужны данные для резюме. Укажите ваше полное имя?");
            } else {
                userDataMap.get(chatId).setEnableAI(false);
                userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_FULLNAME);
                sendMessage(chatId, "Вы отказались от участия ИИ. Ничего, разберемся и без него!");
                sendMessage(chatId, "Теперь нужны данные для резюме. Укажите ваше полное имя?");
            }
        } else {
            sendMessage(chatId, "Вы ввели некорректные данные. Пожалуйста, введите 'Да', 'Нет', 'Yes', 'No', 'Так' или 'Ні'.");
        }
    }

    private void handleResumeFullName(long chatId, String messageText) {
        userDataMap.get(chatId).setResume(new Resume());
        userDataMap.get(chatId).getResume().setFullName(messageText);
        userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_EMAIL);
        sendMessage(chatId, "Отлично, теперь перейдем к контактным данным. Укажите ваш mail?");
    }

    private void handleResumeEmail(long chatId, String messageText) {
        String emailRegex = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";
        if (messageText.matches(emailRegex)) {
            userDataMap.get(chatId).getResume().setEmail(messageText);
            userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_PHONE);
            sendMessage(chatId, "Укажите ваш контактный телефон?");
        } else {
            sendMessage(chatId, "Пожалуйста, введите корректный адрес электронной почты.");
        }
    }

    private void handleResumePhone(long chatId, String messageText) {
        String phoneRegex = "^(\\+?38)?\\s*\\(?0\\d{2}\\)?[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}$";
        if (messageText.trim().matches(phoneRegex)) {
            userDataMap.get(chatId).getResume().setPhone(messageText);
            userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_CITY);
            sendMessage(chatId, "Укажите ваш город проживания?");
        } else {
            sendMessage(chatId, "Пожалуйста, введите корректный номер телефона.");
        }
    }

    private void handleResumeCity(long chatId, String messageText) {
        userDataMap.get(chatId).getResume().setCity(messageText);
        userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_PURPOSE_JOB_SEARCH);
        sendMessage(chatId, "Контактные данные собраны. Теперь укажите вашу цель поиска работы?");
    }

    private void handleResumePurposeJobSearch(long chatId, String messageText) {
        userDataMap.get(chatId).getResume().setObjective(messageText);
        userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_EDUCATION_QUANTITY);
        sendMessage(chatId, "Ваша цель ясна. Теперь займемся вашим образованием. Укажите количество" +
                " учебных заведений, которые вы окончили?");
    }

    private void handleResumeEducationQuantity(long chatId, String messageText) {
        try {
            int numberEducationalInstitutions = Integer.parseInt(messageText.trim());
            userDataMap.get(chatId).setNumberEducationalInstitutions(numberEducationalInstitutions);
            if (numberEducationalInstitutions == 0) {
                userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_EXPERIENCE_QUANTITY);
                sendMessage(chatId, "Понял, вы не окончили ни одного учебного заведения. Принято!");
                sendMessage(chatId, "Укажите количество мест работы где вы работали?");
            } else {
                userDataMap.get(chatId).setCurrentEducationalInstitution(0); // Начинаем с первого учебного заведения
                userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_EDUCATION_NAME);
                sendMessage(chatId, "Введите название учебного заведения номер "
                        + (userDataMap.get(chatId).getCurrentEducationalInstitution() + 1) +
                        ", которое вы окончили:");
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Пожалуйста, введите число.");
        }
    }

    private void handleResumeEducationName(long chatId, String messageText) {
        UserData userData = userDataMap.get(chatId);
        Education education = new Education();
        education.setInstitutionName(messageText);
        userDataMap.get(chatId).getResume().getEducationList().add(education); // Добавляем учебное заведение

        userData.setState(UserData.State.WAITING_FOR_RESUME_EDUCATION_SPECIALITY);
        sendMessage(chatId, "Укажите вашу специальность в учебном заведении номер "
                + (userDataMap.get(chatId).getCurrentEducationalInstitution() + 1) + "?");
    }

    private void handleResumeEducationSpeciality(long chatId, String messageText) {
        UserData userData = userDataMap.get(chatId);
        Education education = userData.getResume().getEducationList().get(userData.getCurrentEducationalInstitution());
        education.setSpecialization(messageText);

        userData.setState(UserData.State.WAITING_FOR_RESUME_EDUCATION_YEARS);
        sendMessage(chatId, "Укажите годы обучения в учебном заведении номер " + (userData.getCurrentEducationalInstitution() + 1) + "?");
    }

    private void handleResumeEducationYears(long chatId, String messageText) {
        UserData userData = userDataMap.get(chatId);
        Education education = userData.getResume().getEducationList().get(userData.getCurrentEducationalInstitution());
        education.setYears(messageText); // Сохраняем годы обучения

        // Увеличиваем индекс текущего учебного заведения
        userData.setCurrentEducationalInstitution(userData.getCurrentEducationalInstitution() + 1);

        // Проверяем, есть ли еще учебные заведения для ввода
        if (userData.getCurrentEducationalInstitution() < userData.getNumberEducationalInstitutions()) {
            userData.setState(UserData.State.WAITING_FOR_RESUME_EDUCATION_NAME);
            sendMessage(chatId, "Введите название учебного заведения номер " + (userData.getCurrentEducationalInstitution() + 1) + ", которое вы окончили:");
        } else {
            userData.setState(UserData.State.WAITING_FOR_RESUME_EXPERIENCE_QUANTITY);
            sendMessage(chatId, "Спасибо! Информация о вашем образовании сохранена. Переходим к вашему опыту работы.");
            sendMessage(chatId, "Укажите количество мест работы где вы работали?");
        }
    }

    private void handleResumeExperienceQuantity(long chatId, String messageText) {
        try {
            int numberJobs = Integer.parseInt(messageText.trim());
            userDataMap.get(chatId).setNumberJobs(numberJobs);
            if (numberJobs == 0) {
                userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_LANGUAGES);
                sendMessage(chatId, "Понятно, вы еще нигде не работали. Принял! " +
                        "Теперь разберемся с вашими навыками и опытом. " +
                        "Если вы выбрали создание резюме при участии ИИ, то можете и не заполнять " +
                        "дальнейшие пункты, если доверяете технологиям. Здесь на ваше усмотрение.");
                sendMessage(chatId, "Итак переходим к последнему этапу касающегося ваших способностей. " +
                        "Укажите языки которыми вы владеете? Если желаете пропустить для автоматического " +
                        "заполнения нажмите 'N'.");
            } else {
                userDataMap.get(chatId).setCurrentJob(0); // Начинаем с первого места работы
                userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_EXPERIENCE_NAME);
                sendMessage(chatId, "Введите название компании номер "
                        + (userDataMap.get(chatId).getCurrentJob() + 1) +
                        ", где вы работали:");
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Пожалуйста, введите число.");
        }
    }

    private void handleResumeExperienceName(long chatId, String messageText) {
        UserData userData = userDataMap.get(chatId);
        WorkExperience workExperience = new WorkExperience();
        workExperience.setCompanyName(messageText);
        userDataMap.get(chatId).getResume().getWorkExperienceList().add(workExperience); // Добавляем место работы

        userData.setState(UserData.State.WAITING_FOR_RESUME_EXPERIENCE_POSITION);
        sendMessage(chatId, "Укажите вашу должность в компании номер "
                + (userDataMap.get(chatId).getCurrentJob() + 1) + "?");
    }

    private void handleResumeExperiencePosition(long chatId, String messageText) {
        UserData userData = userDataMap.get(chatId);
        WorkExperience workExperience = userData.getResume().getWorkExperienceList().get(userData.getCurrentJob());
        workExperience.setPosition(messageText);

        userData.setState(UserData.State.WAITING_FOR_RESUME_EXPERIENCE_YEARS);
        sendMessage(chatId, "Укажите годы работы в компании номер " + (userData.getCurrentJob() + 1) + "?");
    }

    private void handleResumeExperienceYears(long chatId, String messageText) {
        UserData userData = userDataMap.get(chatId);
        WorkExperience workExperience = userData.getResume().getWorkExperienceList().get(userData.getCurrentJob());
        workExperience.setPeriod(messageText); // Сохраняем годы работы

        // Увеличиваем индекс текущего места работы
        userData.setCurrentJob(userData.getCurrentJob() + 1);

        // Проверяем, есть ли еще места работы для ввода
        if (userData.getCurrentJob() < userData.getNumberJobs()) {
            userData.setState(UserData.State.WAITING_FOR_RESUME_EXPERIENCE_NAME);
            sendMessage(chatId, "Введите название компании номер " + (userData.getCurrentJob() + 1) + ", где вы работали:");
        } else {
            userData.setState(UserData.State.WAITING_FOR_RESUME_LANGUAGES);
            sendMessage(chatId, "Отлично! Информация о вашем опыте работы сохранена. " +
                    "Теперь разберемся с вашими навыками и опытом. " +
                    "Если вы выбрали создание резюме при участии ИИ, то можете и не заполнять дальнейшие " +
                    "пункты, если доверяете технологиям. Здесь на ваше усмотрение.");
            sendMessage(chatId, "Итак переходим к последнему этапу касающегося ваших способностей. " +
                    "Укажите языки которыми вы владеете? Если желаете пропустить для автоматического " +
                    "заполнения нажмите 'N'.");
        }
    }

    private void handleResumeLanguages(long chatId, String messageText) {
        if (messageText.trim().equals("N")) {
            messageText = "";
        }
        userDataMap.get(chatId).getResume().setLanguages(messageText);
        userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_SKILLS_AND_ABILITIES);
        sendMessage(chatId, "С языками ясно. Теперь опишите ваши навыки и способности? Если желаете " +
                "пропустить для автоматического заполнения нажмите 'N'.");
    }

    private void handleResumeSkillsAndAbilities(long chatId, String messageText) {
        if (messageText.trim().equals("N")) {
            messageText = "";
        }
        userDataMap.get(chatId).getResume().setSkills(messageText);
        userDataMap.get(chatId).setState(UserData.State.WAITING_FOR_RESUME_ACHIEVEMENTS);
        sendMessage(chatId, "Понятно. Теперь опишите ваши личные достижения и награды если есть? " +
                "Если желаете пропустить для автоматического заполнения нажмите 'N'.");
    }

    private void handleResumeAchievements(long chatId, String messageText) {
        if (messageText.trim().equals("N")) {
            messageText = "";
        }
        userDataMap.get(chatId).getResume().setAchievements(messageText);
        userDataMap.get(chatId).setState(UserData.State.WAITING_CHOICE_METHOD);
        sendMessage(chatId, "Отлично, вся необходимая информация собрана и сохранена. " +
                "Общая информация userData c chatId = " + chatId + ":" + userDataMap.get(chatId));
        sendMessage(chatId, "Теперь сделайти выбор, желаете ли вы просмотреть список выбранных " +
                "вакансий и отправить резюме на конкретно выбранную вакансию, либо отправить " +
                "резюме на все полученные вакансии сразу после ознакомления с ними и полученния " +
                "резюме? Если вы выбрали вариант с выбором конкретной вакансии для отправки резюме " +
                "нажмите '1', если вариант отправки на все полученные вакансии нажмите '2'.");
    }

    private void handleChoiceMethod(long chatId, String messageText) {
        try {
            int choiceMethod = Integer.parseInt(messageText.trim());
            if (choiceMethod == 1 || choiceMethod == 2) {
                userDataMap.get(chatId).setChoiceMethod(choiceMethod);
                if (choiceMethod == 1) {
                    sendMessage(chatId, "Выбор принят. Ожидайте списка заявленных вакансий. " +
                            "Подождите немного, процесс может занять до нескольких минут...");
                    handleMethod(chatId, userDataMap.get(chatId), 1);
                } else if (choiceMethod == 2) {
                    sendMessage(chatId, "Выбор принят. Ожидайте списка заявленных вакансий. " +
                            "Подождите немного, процесс может занять до нескольких минут...");
                    handleMethod(chatId, userDataMap.get(chatId), 2);
                }
            } else {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            sendMessage(chatId, "Пожалуйста, введите '1' или '2' для выбора.");
        }
    }

    private void handleMethod(long chatId, UserData userData, int choiceOption) {
        System.out.println("Working method handleMethod...");
        List<Vacancy> vacancies = new ArrayList<>();

        int countVacancies = userData.getCountVacancies();
        String site = userData.getSite();
        String position = userData.getPosition();
        String city = userData.getCity();

        boolean hasWork = site.toLowerCase().contains("work.ua");
        boolean hasRabota = site.toLowerCase().contains("rabota.ua");

        if (hasWork && hasRabota) {
            vacancies = TelegramJobSearch.handleSearch(true, true, countVacancies,
                    countVacancies, position, city);
        } else if (hasWork) {
            vacancies = TelegramJobSearch.handleSearch(true, false, countVacancies,
                    null, position, city);
        } else if (hasRabota) {
            vacancies = TelegramJobSearch.handleSearch(false, true, null,
                    countVacancies, position, city);
        }

        if (vacancies.isEmpty()) {
            sendMessage(chatId, "По вашему запросу не найдено ни одной вакансии. Не " +
                    "расстраивайтесь, пробуйте дальше!");
            startConversation(chatId);
            return;
        }

        userDataMap.get(chatId).setReceivedVacancies(vacancies);
        sendMessage(chatId, "Заявленный список вакансий:");
        for (Vacancy vacancy : vacancies) {
            sendMessage(chatId, "id = " + vacancy.getId() + "\n" +
                    "Название: " + vacancy.getTitle() + "\n" +
                    "Компания: " + vacancy.getCompanyName() + "\n" +
                    "Город, место: " + vacancy.getCity() + "\n" +
                    "Зарплата: " + vacancy.getSalary() + "\n" +
                    "Сайт размещения: " + vacancy.getSiteName() + "\n" +
                    "Ссылка на вакансию: " + vacancy.getUrl());
        }

        if (choiceOption == 1) {
            userDataMap.get(chatId).setState(UserData.State.WAITING_ID_VACANCY);
            sendMessage(chatId, "Посмотрите, поизучайте, повыбирайте теперь. Когда найдете свой " +
                    "вариант, укажите id вакансии, просто введите число и под данную вакансию вам будет " +
                    "создано резюме. Резюме вы сможете посмотреть предварительно и подтвердите только " +
                    "отправку. Поэтому жду от вас число id вакансии...");
        } else if(choiceOption == 2) {
            userDataMap.get(chatId).setState(UserData.State.WAITING_SEND_RESUME_TO_ALL_VACANCIES);
            String resumeFile = telegramCreateResume.createResume(userDataMap.get(chatId), choiceOption);
            userDataMap.get(chatId).setResumeFile(resumeFile);
            String filePath = "src/main/resources/static/resumes/" + resumeFile;
            sendFile(chatId, filePath);
            sendMessage(chatId, "У вас теперь есть список вакансий и резюме, оцените все при " +
                    "желании и подтвердите отправку резюме на все данные вакансии. В случае отправки " +
                    "введите 'Да', в случае отказа 'Нет'.");
        }
    }

    private void handleSendResumeToAllVacancies(long chatId, String messageText) {
        String cleanedMessage = messageText.trim()
                .replaceAll("[\\s,;:_]+", " ") // Заменяем пробелы и знаки на пробел
                .replaceAll("[^\\w\\u0400-\\u04FF]", "") // Удаляем все, кроме букв и цифр
                .replaceAll("[\\s]+", " "); // Удаляем лишние пробелы

        // Список допустимых ответов
        List<String> validResponses = Arrays.asList(
                "да", "нет", // на русском
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
                sendMessage(chatId, "Шикарный выбор, ожидайте подтверждения об отправке...");
                List<Vacancy> vacancies = userDataMap.get(chatId).getReceivedVacancies();
                StringBuilder sbResult = new StringBuilder();
                int countErrorSend = 0;
                for (int i = 0; i < vacancies.size(); i++) {
                    String result = telegramSendingResumeAndReport.uploadResume(userDataMap.get(chatId),
                            i + 1);
                    if (!result.contains("Ваше резюме успешно отправлено!")) {
                        if (countErrorSend == 1) {
                            sbResult.append("Ошибка при отправке резюме на вакансии с id: ");
                        }
                        sbResult.append(vacancies.get(i).getId() + ", ");
                        countErrorSend++;
                    }
                }
                if (countErrorSend == 0) {
                    sbResult.append("Резюме отправлено на все вакансии. Подтверждение об отправке " +
                            "также на вашем email с прикрепленным резюме. Удачи!");
                } else {
                    String sbResultStr = sbResult.toString();
                    if (sbResultStr.endsWith(", ")) {
                        // Заменяем на ". " с помощью substring
                        sbResultStr = sbResultStr.substring(0, sbResultStr.length() - 2) + ". ";
                        userDataMap.get(chatId).setState(UserData.State.WAITING_RESULT_SENDING_RESUME);
                        sendMessage(chatId, sbResult.toString());
                        sendMessage(chatId, "Теперь если у вас есть какие либо вопросы, завайте! " +
                                "На 5 вопросов готов ответить, на большее извиняйте..");
                        return;
                    }
                }
                userDataMap.get(chatId).setState(UserData.State.WAITING_RESULT_SENDING_RESUME);
                sendMessage(chatId, sbResult.toString());
                sendMessage(chatId, "Теперь если у вас есть какие либо вопросы, завайте! " +
                        "На 5 вопросов готов ответить, на большее извиняйте..");
            } else {
                sendMessage(chatId,"Все с вами ясно, на нет и разговору нет. Собирайтесь силами " +
                        "дальше, когда надумаете обращайтесь...");
                startConversation(chatId);

            }
        } else {
            sendMessage(chatId, "Вы ввели некорректные данные. Пожалуйста, введите 'Да', 'Нет', 'Yes', 'No', 'Так' или 'Ні'.");
        }
    }

    private void handleIdVacancy(long chatId, String messageText) {
        System.out.println("Working method handleIdVacancy");
        try {
            int idVacancy = Integer.parseInt(messageText.trim());
            if ((idVacancy <= 0) || (idVacancy > userDataMap.get(chatId).getReceivedVacancies().size())) {
                throw new NumberFormatException();
            }
            userDataMap.get(chatId).setState(UserData.State.WAITING_CREATE_RESUME);
            userDataMap.get(chatId).setIdVacancyForResume(idVacancy);
            sendMessage(chatId, "Прекрасно! Для вас создается резюме, ожидайте...");
            handleCreateResume(chatId, userDataMap.get(chatId));
            sendMessage(chatId, "Теперь принимайте решение об отправке данного резюме " +
                    "работодателю. Смелее, решайтесь! Вы подтверждаете отправку? Введите 'Да' или 'Нет'.");

        } catch (NumberFormatException e) {
            sendMessage(chatId, "Укажите корректные данные. Это должно быть число от 1 до " +
                    + userDataMap.get(chatId).getReceivedVacancies().size());
        }
    }

    private void handleCreateResume(long chatId, UserData userData) {
        System.out.println("Working method handleCreateResume");
        String resumeFile = telegramCreateResume.createResume(userData, 1);
        userData.setResumeFile(resumeFile);
        String filePath = "src/main/resources/static/resumes/" + resumeFile;
        sendMessage(chatId, "Резюме готово! Принимайте..");
        sendFile(chatId, filePath);
        userData.setState(UserData.State.WAITING_SEND_RESUME);
    }

    private void handleSendResume(long chatId, String messageText) {
        // Очищаем строку от лишних пробелов и знаков препинания
        String cleanedMessage = messageText.trim()
                .replaceAll("[\\s,;:_]+", " ") // Заменяем пробелы и знаки на пробел
                .replaceAll("[^\\w\\u0400-\\u04FF]", "") // Удаляем все, кроме букв и цифр
                .replaceAll("[\\s]+", " "); // Удаляем лишние пробелы

        // Список допустимых ответов
        List<String> validResponses = Arrays.asList(
                "да", "нет", // на русском
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
                String result = telegramSendingResumeAndReport.uploadResume(userDataMap.get(chatId),
                        userDataMap.get(chatId).getIdVacancyForResume());
                userDataMap.get(chatId).setState(UserData.State.WAITING_RESULT_SENDING_RESUME);
                sendMessage(chatId, result);
                sendMessage(chatId, "Теперь если у вас есть какие либо вопросы, завайте! " +
                        "На 5 вопросов готов ответить, на большее извиняйте..");
            } else {
                sendMessage(chatId,"Ну и хрен с вами, как хотите, гуляйте, отдыхайте... " +
                        "Когда снова решитесь обращайтесь.");
                startConversation(chatId);

            }
        } else {
            sendMessage(chatId, "Вы ввели некорректные данные. Пожалуйста, введите 'Да', 'Нет', 'Yes', 'No', 'Так' или 'Ні'.");
        }
    }

    private void handleResultChattingWithTheBot(long chatId, String messageText) {
        UserData userData = userDataMap.get(chatId);
        if (ProfanityFilter.containsProfanity(messageText, userData)) {
            String responceFromFilterProfanity = ProfanityFilter.reactionToSwearing(userData);
            if (userData.getCountBadMessage() == 4) {
                userData.setCountBadMessage(0);
                userData.setWorkedMethodHandleApology(false);
                sendMessage(chatId, responceFromFilterProfanity);
                userData.setState(UserData.State.END);
                return;
            }
            sendMessage(chatId, responceFromFilterProfanity);
            return;
        }

        if (userData.getCountBadMessage() == 3 && userData.isPresenceApologySwearing3() &&
                !userData.isWorkedMethodHandleApology()) {
            sendMessage(chatId, "Другое дело! Чтобы больше не слышал подобного... Теперь готов " +
                    "дальше общаться, если вы только не исчерпали свой лимит в 5 вопросов мне, который " +
                    "я вам подарил..");
            userData.setPresenceApologySwearing3(false);
            return;
        } else if (userData.getCountBadMessage() == 3 && !userData.isPresenceApologySwearing3()) {
            userData.setState(UserData.State.WAITING_APOLOGY);
            sendMessage(chatId, "Извиняйся нах..! До этих пор общаться с тобой не буду!");
            return;
        }

        String responce = "";

        if (userData.getCountQuestionToBot() < 5) {
            responce = openAIService.generateCompletion(messageText);
            sendMessage(chatId, responce);
            userData.setCountQuestionToBot(userData.getCountQuestionToBot() + 1);
        }
        if (userData.getCountQuestionToBot() == 5) {
            sendMessage(chatId, "Все достаточно, я ж сказал 5 вопросов, не больше! Не хочу более. " +
                    "Если отправите новые резюме на другие вакансии, может захочу пообщаться " +
                    "с вами еще.. А так удачного отклика и поднятия денег! Конечно если никто не " +
                    "отозветься, обращайтесь снова, помогу!");
            userDataMap.get(chatId).setState(UserData.State.END);
            userData.setCountQuestionToBot(0);
            userData.setWorkedMethodHandleApology(false);
        }
    }

    private void handleApology(long chatId, String messageText) {
        UserData userData = userDataMap.get(chatId);
        if (ProfanityFilter.containsProfanity(messageText, userData)) {
            String responceFromFilterProfanity = ProfanityFilter.reactionToSwearing(userData);
            userData.setCountBadMessage(0);
            userData.setWorkedMethodHandleApology(false);
            sendMessage(chatId, responceFromFilterProfanity);
            userData.setState(UserData.State.END);
            return;
        }
        if (!userData.isPresenceApologySwearing3()) {
            sendMessage(chatId, "Извиняйся нах..! До этих пор общаться с тобой не буду!");
        } else {
            userData.setState(UserData.State.WAITING_RESULT_SENDING_RESUME);
            userData.setWorkedMethodHandleApology(true);
            sendMessage(chatId, "Другое дело! Чтобы больше не слышал подобного... Теперь готов " +
                    "дальше общаться, если вы только не исчерпали свой лимит в 5 вопросов мне, который " +
                    "я вам подарил..");
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

    public void sendFile(long chatId, String filePath) {
        File file = new File(filePath);

        // Проверка существования файла
        if (!file.exists()) {
            sendMessage(chatId, "Файл не найден: " + filePath);
            sendMessage(chatId, "Что то произошло не так при формировании резюме, не отчаивайтесь, " +
                    "попробуйте снова.");
            startConversation(chatId);
            return;
        }

        // Создаем объект для отправки документа
        SendDocument sendDocument = new SendDocument();
        sendDocument.setChatId(String.valueOf(chatId));
        sendDocument.setDocument(new InputFile(file)); // Используем InputFile с объектом File

        try {
            execute(sendDocument); // Отправляем файл
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
