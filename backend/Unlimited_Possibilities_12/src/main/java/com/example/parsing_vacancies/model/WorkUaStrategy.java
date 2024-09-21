package com.example.parsing_vacancies.model;

import com.example.parsing_vacancies.parameters.City;
import com.example.parsing_vacancies.parameters.Language;
import com.example.parsing_vacancies.parameters.TimeDate;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;


import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WorkUaStrategy implements Strategy {
    private static final String URL_FORMAT = "https://www.work.ua/ru/jobs-kyiv-%s/?page=%d";
    private static final String URL_FORMAT_DIAPASON_TIME =
            "https://www.work.ua/%s/jobs-%s-%s/?days=%d&page=%d";

    @Override
    public List<Vacancy> getVacancies(String position) {
        List<Vacancy> vacancies = new ArrayList<>();
        Document doc = null;
        for (int pageNumber = 1; ;pageNumber++) {
            doc = getDocument(position, pageNumber);
            List<Vacancy> vacanciesFromPageNumber = getVacanciesFromDocument(doc);
            if (vacanciesFromPageNumber.size() == 0) break;
            vacancies.addAll(vacanciesFromPageNumber);
            System.out.println(vacancies.size());
        }
        return vacancies;
    }

    @Override
    public List<Vacancy> getVacancies(Language language, City city, String position, TimeDate time) {
        List<Vacancy> vacancies = new ArrayList<>();
        Document doc = null;
        for (int pageNumber = 1; ;pageNumber++) {
            doc = getDocumentWithParam(language, city, position, time, pageNumber);
            List<Vacancy> vacanciesFromPageNumber = getVacanciesFromDocument(doc);
            if (vacanciesFromPageNumber.size() == 0) break;
            vacancies.addAll(vacanciesFromPageNumber);
            System.out.println(vacancies.size());
        }
        return vacancies;
    }

    private List<Vacancy> getVacanciesFromDocument(Document document) {
        List<Vacancy> vacancies = new ArrayList<>();
        Elements elementVacancies = document.getElementsByAttributeValue("tabindex", "0");
        if (elementVacancies.isEmpty()) return vacancies;
        for (Element elementVacancy : elementVacancies) {
            try {
                Vacancy vacancy = new Vacancy();
                vacancy.setTitle(elementVacancy.getElementsByAttribute("title").first().text().trim());
                vacancy.setUrl("https://www.work.ua" + elementVacancy.getElementsByAttribute("href").attr("href").trim());
                //salary
                Elements tagsDiv = elementVacancy.getElementsByTag("div");
                String salary = tagsDiv.get(3).text().trim();
                salary = salary.replaceAll("[^\\d–?грн]", "");
                Pattern pattern = Pattern.compile("(\\d)+(–?)?(\\d)*(грн)?");
                Matcher matcher = pattern.matcher(salary);
                boolean found = matcher.matches();
                if (found) {
                    vacancy.setSalary(salary);
                } else {
                    vacancy.setSalary("");
                }
                //company name
                if (!vacancy.getSalary().equals("")) {
                    Element elementCompanyName = tagsDiv.get(4).getElementsByTag("span").get(1);
                    String companyName = elementCompanyName.hasText() ? elementCompanyName.text().trim() : "";
                    vacancy.setCompanyName(companyName);
                } else {
                    Elements elementsCompanyName = tagsDiv.get(3).getElementsByTag("span");
                    Element elementCompanyName = elementsCompanyName.size() > 0 ? elementsCompanyName.get(1) : tagsDiv.get(2).getElementsByTag("span").get(1);
                    String companyName = elementCompanyName.hasText() ? elementCompanyName.text().trim() : "";
                    vacancy.setCompanyName(companyName);
                }
                //location
                if (!vacancy.getSalary().equals("")) {
                    String location = tagsDiv.get(4).text().replaceAll(vacancy.getCompanyName(), "");
                    vacancy.setCity(location);
                } else {
                    String location = tagsDiv.get(3).text().replaceAll(vacancy.getCompanyName(), "");
                    if (location.equals("Чтобы сохранить вакансию, нужно войти или зарегистрироваться.")) {
                        location = tagsDiv.get(2).text().replaceAll(vacancy.getCompanyName(), "");
                        vacancy.setCity(location);
                    } else {
                        vacancy.setCity(location);
                    }
                }
                //siteName
                vacancy.setSiteName("https://www.work.ua");

                System.out.println(vacancy.getTitle());
                System.out.println(vacancy.getSalary());
                System.out.println(vacancy.getUrl());
                System.out.println(vacancy.getCompanyName());
                System.out.println(vacancy.getCity());
                System.out.println(vacancy.getSiteName());
                vacancies.add(vacancy);
                System.out.println("----------------------------");
                //System.out.println(elementVacancy.outerHtml());
                //System.out.println("------------------");
            } catch (Exception e) {
                continue;
            }
        }
        return vacancies;
    }

    protected Document getDocument(String position, int page) {
        Document doc = null;
        try {
            String url = String.format(URL_FORMAT, position, page);
            doc = Jsoup.connect(url).
                    userAgent("Chrome/121.0.0.0 Safari/537.36").referrer("").get();
            String s = doc.html();
        } catch (IOException e) {
            new RuntimeException(e);
        }
        return doc;
    }

    protected Document getDocumentWithParam(Language language, City city, String position, TimeDate time, int page) {
        LanguageWorkUa languageWorkUa = null;
        CityWorkUa cityWorkUa = null;
        DaysWorkUa daysWorkUa = null;
        switch (language) {
            case ENGLISH -> languageWorkUa = LanguageWorkUa.ENGLISH;
            case UKRAINIAN -> languageWorkUa = LanguageWorkUa.UKRAINIAN;
            default -> languageWorkUa = LanguageWorkUa.RUSSIAN;
        }
        switch (city) {
            case ODESSA -> cityWorkUa = CityWorkUa.Odessa;
            case KHARKOV -> cityWorkUa = CityWorkUa.Kharkov;
            case DNEPROPETROVSK -> cityWorkUa = CityWorkUa.Dnepropetrovsk;
            default -> cityWorkUa = CityWorkUa.Kiev;
        }
        switch (time) {
            case ONE_DAY -> daysWorkUa = DaysWorkUa.ONE_DAY;
            case THREE_DAYS, SEVEN_DAYS -> daysWorkUa = DaysWorkUa.SEVEN_DAYS;
            case FOURTEEN_DAYS -> daysWorkUa = DaysWorkUa.FOURTEEN_DAYS;
            default -> daysWorkUa = DaysWorkUa.THIRTY_DAYS;

        }
        Document doc = null;
        try {
            String url = String.format(URL_FORMAT_DIAPASON_TIME,
                    languageWorkUa.getStr(), cityWorkUa.getStr(), position, daysWorkUa.getDays(), page);
            doc = Jsoup.connect(url).
                    userAgent("Chrome/121.0.0.0 Safari/537.36").referrer("").get();
            String s = doc.html();
        } catch (IOException e) {
            new RuntimeException(e);
        }
        return doc;
    }

    protected Document getDocumentFromUrl(String url) {
        Document doc = null;
        try {
            doc = Jsoup.connect(url).
                    userAgent("Chrome/121.0.0.0 Safari/537.36").referrer("").get();
            String s = doc.html();
            System.out.println(s);
        } catch (IOException e) {
            new RuntimeException(e);
        }
        return doc;
    }

    public enum DaysWorkUa {
        ONE_DAY (122),
        SEVEN_DAYS (123),
        FOURTEEN_DAYS (124),
        THIRTY_DAYS (125);

        private int days;

        DaysWorkUa(int days) {
            this.days = days;
        }

        public int getDays() {
            return days;
        }
    }

    public enum LanguageWorkUa {
        ENGLISH ("en"),
        UKRAINIAN (""),
        RUSSIAN ("ru");

        private String str;

        LanguageWorkUa (String str) {
            this.str = str;
        }

        public String getStr() {
            return str;
        }
    }

    public enum CityWorkUa {
        Kiev ("kyiv"),
        Dnepropetrovsk ("dnipro"),
        Kharkov ("kharkiv"),
        Odessa("odesa");

        private String str;

        CityWorkUa (String str) {
            this.str = str;
        }

        public String getStr() {
            return str;
        }
    }

    public static void main(String[] args) throws IOException {
        //new WorkUaStrategy().getVacancies("manager");

        Document doc = new WorkUaStrategy().getDocument("manager", 1);
        System.out.println(doc.html());
        /*Document docPage2 = new WorkUaStrategy().getDocument("manager", 13);
        String s2 = docPage2.html();
        Elements elementListVacancies = docPage2.getElementsByAttributeValue("tabindex", "0");
        for (Element elementListVacancy : elementListVacancies) {
            System.out.println(elementListVacancy.outerHtml());
            System.out.println("------------------");
        }
        System.out.println(elementListVacancies.size());*/
    }
}
