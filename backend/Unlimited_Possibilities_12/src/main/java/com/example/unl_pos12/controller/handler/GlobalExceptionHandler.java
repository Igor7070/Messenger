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
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Ошибка: " + e.getMessage());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<String> handleEntityNotFoundException(EntityNotFoundException e) {
        System.out.println("Error: " + e.getMessage());
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Ошибка: " + e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<String> handleIllegalStateException(IllegalStateException e) {
        System.out.println("Error: " + e.getMessage());
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Ошибка: " + e.getMessage());
    }
}
