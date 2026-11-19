package com.example.unlimitedpossibilities12.messenger;

import com.example.unlimitedpossibilities12.api.User;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Имена, которыми люди себя назвали, — по именам входа.
 *
 * Названия личных переписок на сервере сложены из имён входа («Kenzya_Igor»),
 * и экраны переписки знают о собеседнике только эту половину названия. Чтобы
 * и там человек был подписан так, как себя назвал, запоминаем соответствие
 * «имя входа → выбранное имя» всякий раз, когда с сервера приходит список
 * людей, и спрашиваем его при отрисовке.
 *
 * Пусто — значит показываем имя входа: ничего не теряется.
 */
public final class PeopleNames {

    private static final Map<String, String> CHOSEN = new ConcurrentHashMap<>();

    private PeopleNames() {
    }

    public static void remember(List<User> people) {
        if (people == null) return;
        for (User person : people) remember(person);
    }

    public static void remember(User person) {
        if (person == null || person.getUsername() == null) return;
        String chosen = person.getDisplayName();
        if (chosen == null || chosen.trim().isEmpty()) {
            CHOSEN.remove(person.getUsername());
        } else {
            CHOSEN.put(person.getUsername(), chosen.trim());
        }
    }

    /** Как показать человека, о котором известно только имя входа. */
    public static String of(String login) {
        if (login == null) return "";
        String chosen = CHOSEN.get(login);
        return chosen == null ? login : chosen;
    }
}
