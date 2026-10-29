package com.example.unl_pos12.model.chat_ai;

import com.example.unl_pos12.model.messenger.Message;

import java.util.List;

public class GenerateMessageRequest {
    private List<Message> history;
    private String preference;

    // Конструктор по умолчанию (нужен для Jackson/JSON)
    public GenerateMessageRequest() {
    }

    // Конструктор с параметрами (удобно для тестов)
    public GenerateMessageRequest(List<Message> history, String preference) {
        this.history = history;
        this.preference = preference;
    }

    // Геттеры и сеттеры
    public List<Message> getHistory() {
        return history;
    }

    public void setHistory(List<Message> history) {
        this.history = history;
    }

    public String getPreference() {
        return preference;
    }

    public void setPreference(String preference) {
        this.preference = preference;
    }

    // Опционально: toString для отладки
    @Override
    public String toString() {
        return "GenerateMessageRequest{" +
                "history=" + history +
                ", preference='" + preference + '\'' +
                '}';
    }
}
