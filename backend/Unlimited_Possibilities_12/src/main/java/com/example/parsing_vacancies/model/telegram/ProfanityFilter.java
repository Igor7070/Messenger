package com.example.parsing_vacancies.model.telegram;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class ProfanityFilter {
    // Список матерных слов
    private static final List<String> badWords = Arrays.asList("хуй", "блять", "блядь", "пизд",
            "ебат", "блеат", "БЕСПИЗД", "БЛЯ", "ВЗЪЕБ", "ВПИЗД", "взьебн", "ебн", "хуя", "бляд",
            "хуе", "хує", "ебал", "вхуй", "вхуя", "выеб", "выхуя", "вьеб", "говноеб", "голоебица", "хер",
            "доебывать", "долбоеб", "дуроеб", "ебан", "ебля", "ебну", "еб тво", "ебтв", "заеб",
            "наеб", "уеб", "хули", "шароеб", "еблан", "ебаш", "ебен", "едрен", "запизжив", "ебуч",
            "хуи", "бля", "дроч", "забздет", "запздет", "забздел", "пздел", "пзди", "бзди", "сука",
            "суки", "сучий", "новохудоносок", "хуї", "їбат", "їбна", "єбан", "єблан", "єбаш", "їбаш", "заїб",
            "довбоеб", "пізд", "блят", "їбан", "єб тво", "єбтв", "їбуч", "єбуч", "забздіт", "забздід",
            "забздів", "пздів", "єбла", "їбла", "єдрєн", "єдрен", "їбен", "їбєн", "шароєб", "дуроєб",
            "уєб", "їба", "ибану", "їблан", "сучий", "хєр", "хує", "їдрен", "їдрєн", "єдрєн", "єдрен",
            "пызд", "fuck", "блеать", "сцук", "пиздабол", "пиздобол");

    private static final List<String> apologies = Arrays.asList("извини", "извиняюс", "пощади",
            "прости", "каюсь", "я не прав", "прошу прощения", "вибач", "сорян", "сори", "сорри", "sorry",
            "sorri", "прошу вибачення", "звиняй");


    // Метод для проверки наличия матерных слов или фраз в тексте
    public static boolean containsProfanity(String messageText, UserData userData) {
        String lowerCaseMessage = messageText.toLowerCase();

        // Проверка наличие каждого матерного слова или фразы с любыми дополнениями
        for (String badWord : badWords) {
            // Создаем шаблон для регулярного выражения
            String patternString = badWord + "\\w*";
            Pattern pattern = Pattern.compile(patternString);
            if (pattern.matcher(lowerCaseMessage).find()) {
                userData.setCountBadMessage(userData.getCountBadMessage() + 1);
                return true;
            }
        }
        if (userData.getCountBadMessage() == 3) {
            for (String apology : apologies) {
                String patternStringApol = apology + "\\w*";
                Pattern patternApol = Pattern.compile(patternStringApol);
                if (patternApol.matcher(lowerCaseMessage).find()) {
                    userData.setPresenceApologySwearing3(true);
                }
            }
        }
        return false;
    }

    public static String reactionToSwearing(UserData userData) {
        String responce = "";
        int countBadMessage = userData.getCountBadMessage();
        switch (countBadMessage) {
            case 1:
                responce = "Эй дружище, аккуратнее, со мной без мата общайся, первый раз по хорошему " +
                        "предупреждаю!";
                break;
            case 2:
                responce = "Блять, я ж сказал один раз, ты совсем путаешь с кем разговариваешь? Не " +
                        "заставляй проводить меры, ни одного слова мата. Понятно блять?";
                break;
            case 3:
                responce = "Так что, тебя отпиздить? Даю еще шанс один. Или извиняйся немедленно или " +
                        "потом пеняй на себя...";
                break;
            case 4: responce = "Ну все, придется тебе дать пизды, не понял человек, хотел по хорошему! " +
                    "Я знаю твое местонахождение, ожидай... До встречи!";
                break;

        }
        return responce;
    }
}
