package com.example.unl_pos12.controller;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Замер времени до базы — чтобы понять, где уходят секунды на открытии
 * страницы: в самой выборке, в соединении или в сборке ответа. Отдаёт
 * только числа, ничего из данных.
 */
@RestController
public class DiagnosticsController {

    @PersistenceContext
    private EntityManager entityManager;

    @GetMapping("/api/diag/db")
    public Map<String, Object> db() {
        Map<String, Object> result = new LinkedHashMap<>();

        long started = System.nanoTime();
        entityManager.createNativeQuery("SELECT 1").getSingleResult();
        result.put("selectOneMs", (System.nanoTime() - started) / 1_000_000);

        started = System.nanoTime();
        entityManager.createNativeQuery("SELECT 1").getSingleResult();
        result.put("secondSelectMs", (System.nanoTime() - started) / 1_000_000);

        started = System.nanoTime();
        Object users = entityManager.createNativeQuery("SELECT COUNT(*) FROM user").getSingleResult();
        result.put("countUsersMs", (System.nanoTime() - started) / 1_000_000);
        result.put("users", String.valueOf(users));

        started = System.nanoTime();
        Object messages = entityManager.createNativeQuery("SELECT COUNT(*) FROM message").getSingleResult();
        result.put("countMessagesMs", (System.nanoTime() - started) / 1_000_000);
        result.put("messages", String.valueOf(messages));

        // Где стоит само приложение. Том у Railway привязан к региону, и
        // служба переезжает к нему: если база осталась в другом регионе,
        // каждый запрос начинает ходить через полмира.
        result.put("appRegion", String.valueOf(System.getenv("RAILWAY_REPLICA_REGION")));
        result.put("dbHost", String.valueOf(System.getenv("MYSQLHOST")));

        return result;
    }
}
