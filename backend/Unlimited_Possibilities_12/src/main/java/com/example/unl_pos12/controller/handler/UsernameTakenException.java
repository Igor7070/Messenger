package com.example.unl_pos12.controller.handler;

/**
 * Имя входа уже занято.
 *
 * Отдельный тип нужен, чтобы ответить 409 и понятным текстом вместо общей
 * пятисотки: экран регистрации показывает человеку текст ответа как есть.
 */
public class UsernameTakenException extends RuntimeException {
    public UsernameTakenException(String message) {
        super(message);
    }
}
