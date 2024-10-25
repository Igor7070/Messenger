package com.example.parsing_vacancies.model.telegram;

import com.example.parsing_vacancies.model.Vacancy;
import com.example.parsing_vacancies.model.resume.Resume;

import java.util.List;

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
    private int numberEducationalInstitutions;
    private int currentEducationalInstitution; // Индекс текущего учебного заведения
    private int numberJobs;
    private int currentJob; // Индекс текущего места работы
    private Resume resume;
    private int choiceMethod;
    private List<Vacancy> receivedVacancies;
    private int idVacancyForResume;
    private String resumeFile;

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
        WAITING_FOR_RESUME_EDUCATION_QUANTITY,
        WAITING_FOR_RESUME_EDUCATION_NAME,
        WAITING_FOR_RESUME_EDUCATION_SPECIALITY,
        WAITING_FOR_RESUME_EDUCATION_YEARS,
        WAITING_FOR_RESUME_EXPERIENCE_QUANTITY,
        WAITING_FOR_RESUME_EXPERIENCE_NAME,
        WAITING_FOR_RESUME_EXPERIENCE_POSITION,
        WAITING_FOR_RESUME_EXPERIENCE_YEARS,
        WAITING_FOR_RESUME_LANGUAGES,
        WAITING_FOR_RESUME_SKILLS_AND_ABILITIES,
        WAITING_FOR_RESUME_ACHIEVEMENTS,
        WAITING_CHOICE_METHOD,
        WAITING_ID_VACANCY,
        WAITING_CREATE_RESUME,
        WAITING_SEND_RESUME,
        WAITING_RESULT_SENDING_RESUME
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

    public int getNumberEducationalInstitutions() {
        return numberEducationalInstitutions;
    }

    public void setNumberEducationalInstitutions(int numberEducationalInstitutions) {
        this.numberEducationalInstitutions = numberEducationalInstitutions;
    }

    public int getCurrentEducationalInstitution() {
        return currentEducationalInstitution;
    }

    public void setCurrentEducationalInstitution(int currentEducationalInstitution) {
        this.currentEducationalInstitution = currentEducationalInstitution;
    }

    public int getNumberJobs() {
        return numberJobs;
    }

    public void setNumberJobs(int numberJobs) {
        this.numberJobs = numberJobs;
    }

    public int getCurrentJob() {
        return currentJob;
    }

    public void setCurrentJob(int currentJob) {
        this.currentJob = currentJob;
    }

    public Resume getResume() {
        return resume;
    }

    public void setResume(Resume resume) {
        this.resume = resume;
    }

    public int getChoiceMethod() {
        return choiceMethod;
    }

    public void setChoiceMethod(int choiceMethod) {
        this.choiceMethod = choiceMethod;
    }

    public int getIdVacancyForResume() {
        return idVacancyForResume;
    }

    public void setIdVacancyForResume(int idVacancyForResume) {
        this.idVacancyForResume = idVacancyForResume;
    }

    public List<Vacancy> getReceivedVacancies() {
        return receivedVacancies;
    }

    public void setReceivedVacancies(List<Vacancy> receivedVacancies) {
        this.receivedVacancies = receivedVacancies;
    }

    public String getResumeFile() {
        return resumeFile;
    }

    public void setResumeFile(String resumeFile) {
        this.resumeFile = resumeFile;
    }

    @Override
    public String toString() {
        return "UserData{" +
                "site='" + site + '\'' +
                ", position='" + position + '\'' +
                ", city='" + city + '\'' +
                ", countVacancies=" + countVacancies +
                ", state=" + state +
                ", accessToken='" + accessToken + '\'' +
                ", emailGoogle='" + emailGoogle + '\'' +
                ", firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", enableAI=" + enableAI +
                ", numberEducationalInstitutions=" + numberEducationalInstitutions +
                ", currentEducationalInstitution=" + currentEducationalInstitution +
                ", numberJobs=" + numberJobs +
                ", currentJob=" + currentJob +
                ", resume=" + resume +
                '}';
    }
}
