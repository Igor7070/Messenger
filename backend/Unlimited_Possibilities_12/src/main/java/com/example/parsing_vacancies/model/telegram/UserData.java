package com.example.parsing_vacancies.model.telegram;

public class UserData {
    private String site;
    private String position;
    private String city;
    private int countVacancies;
    private State state;
    private String accessToken;
    private String emailGoogle;
    private String firstName;
    private String lastName;
    private boolean enableAI;

    public enum State {
        WAITING_FOR_START,
        WAITING_FOR_SITE,
        WAITING_FOR_POSITION,
        WAITING_FOR_CITY,
        WAITING_FOR_COUNT,
        WAITING_FOR_AUTHORIZATION,
        WAITING_FOR_RESUME_ENABLE_AI,
        WAITING_FOR_RESUME_FULLNAME,
        WAITING_FOR_RESUME_EMAIL,
        WAITING_FOR_RESUME_PHONE,
        WAITING_FOR_RESUME_CITY,
        WAITING_FOR_RESUME_PURPOSE_JOB_SEARCH,
        WAITING_FOR_RESUME_EDUCATION,
        WAITING_FOR_RESUME_EXPERIENCE,
        WAITING_FOR_RESUME_LANGUAGES,
        WAITING_FOR_RESUME_SKILLS_AND_ABILITIES,
        WAITING_FOR_RESUME_ACHIEVEMENTS
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

    public State getState() { return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getEmailGoogle() {
        return emailGoogle;
    }

    public void setEmailGoogle(String emailGoogle) {
        this.emailGoogle = emailGoogle;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public boolean isEnableAI() {
        return enableAI;
    }

    public void setEnableAI(boolean enableAI) {
        this.enableAI = enableAI;
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
