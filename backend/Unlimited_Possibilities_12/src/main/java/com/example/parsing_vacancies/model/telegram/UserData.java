package com.example.parsing_vacancies.model.telegram;

public class UserData {
    private String site;
    private String position;
    private String city;
    private int countVacancies;
    private boolean autorization;
    private State state;

    public enum State {
        WAITING_FOR_START,
        WAITING_FOR_SITE,
        WAITING_FOR_POSITION,
        WAITING_FOR_CITY,
        WAITING_FOR_COUNT,
        WAITING_FOR_AUTHORIZATION
    }

    public UserData() {
        this.state = State.WAITING_FOR_START; // Начальное состояние
    }

    // Геттеры и сеттеры
    public String getSite() {
        return site;
    }
    public void setSite(String site) {
        this.site = site;
    }

    public String getPosition() {
        return position;
    }
    public void setPosition(String position) {
        this.position = position;
    }

    public String getCity() {
        return city;
    }
    public void setCity(String city) {
        this.city = city;
    }

    public int getCountVacancies() {
        return countVacancies;
    }
    public void setCountVacancies(int countVacancies) {
        this.countVacancies = countVacancies;
    }

    public boolean isAutorization() {
        return autorization;
    }

    public void setAutorization(boolean autorization) {
        this.autorization = autorization;
    }

    public State getState() { return state;
    }

    public void setState(State state) {
        this.state = state;
    }



    @Override
    public String toString() {
        return "Запрос: " +
                "Сайт='" + site + '\'' +
                ", Должность='" + position + '\'' +
                ", Город='" + city + '\'' +
                ", Количество=" + countVacancies;
    }
}
