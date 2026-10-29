package com.example.unl_pos12.controller.handler;

/**
 * Неверное имя входа или пароль.
 *
 * Отдельный тип нужен по той же причине, что и {@link UsernameTakenException}:
 * обычная опечатка в пароле — это отказ, а не сбой сервера. Прежде она летела
 * общим RuntimeException, превращалась в 500 и печатала в журнал полный стек
 * вызовов, так что настоящую поломку среди таких записей было не разглядеть.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
