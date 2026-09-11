package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    List<User> findByOnline(boolean online);

    /**
     * Сколько пользователей всё ещё держат чат в своём списке приватных чатов.
     * Считаем по связи, а не по разбору имени чата: имя секретного чата имеет
     * вид «A_B_secret», а имена пользователей сами могут содержать «_»,
     * поэтому split("_") давал неверный результат.
     */
    @Query("SELECT COUNT(u) FROM User u JOIN u.privateChats c WHERE c.id = :chatId")
    long countOwnersOfChat(@Param("chatId") Long chatId);
}
