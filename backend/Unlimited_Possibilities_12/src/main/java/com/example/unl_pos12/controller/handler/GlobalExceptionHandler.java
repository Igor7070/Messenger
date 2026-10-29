package com.example.unl_pos12.controller.handler;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception e) {
        // Логируйте ошибку
        System.out.println("Error: " + e.getMessage());
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleEntityNotFoundException(EntityNotFoundException e) {
        System.out.println("Error: " + e.getMessage());
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Error: " + e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleIllegalStateException(IllegalStateException e) {
        System.out.println("Error: " + e.getMessage());
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Error: " + e.getMessage());
    }

    /** Неверный логин или пароль — отказ, а не сбой: 401 и без стека в журнале. */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<String> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(e.getMessage());
    }

    /** Имя входа занято — обычный отказ при регистрации, а не сбой сервера. */
    @ExceptionHandler(UsernameTakenException.class)
    public ResponseEntity<String> handleUsernameTaken(UsernameTakenException e) {
        System.out.println("Registration refused: " + e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
