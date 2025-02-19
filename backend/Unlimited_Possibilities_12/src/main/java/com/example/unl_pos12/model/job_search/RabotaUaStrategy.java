package com.example.unl_pos12.model.job_search;

import com.example.unl_pos12.parameters.City;
import com.example.unl_pos12.parameters.Language;
import com.example.unl_pos12.parameters.TimeDate;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RabotaUaStrategy implements Strategy {
    private static final String URL_FORMAT = "https://robota.ua/ru/zapros/%s/kyiv?page=%d";
    private static final String URL_FORMAT_DIAPASON_TIME =
            "https://robota.ua/%s/zapros/%s/%s?page=%d";
    private int countRecordedVacancies = 0;
    private static int countFromRemoteDriver = 0; //для удаленного WebDriver
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static final long TIMEOUT = 55; // Время таймаута в секундах

    @Override
    public List<Vacancy> getVacancies(String position, Integer maxVacancies) {
        List<Vacancy> vacancies = new ArrayList<>();
        for (int pageNumber = 1; ;pageNumber++) {
            List<Vacancy> vacanciesFromPageNumber = getVacanciesBySilenium(position,
                    maxVacancies, pageNumber);
            //System.out.println("vacanciesFromPageNumberSize: " + vacanciesFromPageNumber.size());
            if (vacanciesFromPageNumber.size() == 0) break;
            vacancies.addAll(vacanciesFromPageNumber);
            System.out.println(vacancies.size());
            if (maxVacancies != null) {
                if (countRecordedVacancies == maxVacancies) {
                    break;
                }
            }
        }
        return vacancies;
    }

    /*private List<Vacancy> getVacanciesBySileniumWithTimeout(String position, int pageNumber) {
        Future<List<Vacancy>> future = scheduler.submit(() -> getVacanciesBySilenium(position, pageNumber));

        try {
            // Ждем завершения задачи с таймаутом
            return future.get(TIMEOUT, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("Время вышло! Повторная попытка...");
            // Если время вышло, прерываем выполнение и повторяем попытку
            future.cancel(true); // Отменяем предыдущую задачу
            return getVacanciesBySileniumWithTimeout(position, pageNumber); // Рекурсивный вызов
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace(); // Обрабатываем другие исключения
            return new ArrayList<>(); // Возвращаем пустой список в случае ошибки
        }
    }*/

    @Override
    public List<Vacancy> getVacancies(Language language, City city, String position, TimeDate time,
                                      Integer maxVacancies) {
        List<Vacancy> vacancies = new ArrayList<>();
        for (int pageNumber = 1; ;pageNumber++) {
            List<Vacancy> vacanciesFromPageNumber = getVacanciesBySileniumWithParam(language, city,
                    position, time, maxVacancies, pageNumber);
            //System.out.println("vacanciesFromPageNumberSize: " + vacanciesFromPageNumber.size());
            if (vacanciesFromPageNumber.size() == 0) break;
            vacancies.addAll(vacanciesFromPageNumber);
            System.out.println(vacancies.size());
            if (maxVacancies != null) {
                if (countRecordedVacancies == maxVacancies) {
                    break;
                }
            }
        }
        return vacancies;
    }

    /*private List<Vacancy> getVacanciesBySileniumWithParamWithTimeout(Language language, City city, String position, TimeDate time, int pageNumber) {
        Future<List<Vacancy>> future = scheduler.submit(() -> getVacanciesBySileniumWithParam(language, city, position, time, pageNumber));

        try {
            // Ждем завершения задачи с таймаутом
            return future.get(TIMEOUT, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.out.println("Время вышло! Повторная попытка...");
            // Если время вышло, прерываем выполнение и повторяем попытку
            future.cancel(true); // Отменяем предыдущую задачу
            return getVacanciesBySileniumWithTimeout(position, pageNumber); // Рекурсивный вызов
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace(); // Обрабатываем другие исключения
            return new ArrayList<>(); // Возвращаем пустой список в случае ошибки
        }
    }*/

    private List<Vacancy> getVacanciesBySilenium(String position, Integer maxVacancies, int page) {
        List<Vacancy> vacancies = new ArrayList<>();
        int elementVacanciesSize = 0;
        WebDriver driver = null;

        try {
            String url = String.format(URL_FORMAT, position, page);

            System.out.println("Initializing WebDriver...");
            //Для Railway
            String remoteUrl = "https://standalone-chrome-production-5dca.up.railway.app/wd/hub"; // Замените на ваш URL
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless"); // Запуск без графического интерфейса
            options.addArguments("--disable-gpu");
            options.addArguments("--lang=" + "ru"); // Установка языка в зависимости от параметра, например, "ru" или "en"
            driver = new RemoteWebDriver(new URL(remoteUrl), options);

            //Для локальной работы
            //driver = new ChromeDriver();
            System.out.println("WebDriver initialized successfully.");

            driver.manage().timeouts().implicitlyWait(5, TimeUnit.SECONDS);
            driver.get(url);

            /*WebElement appRootElement = driver.findElement(By.cssSelector("app-root"));
            String appRootContent = appRootElement.getAttribute("outerHTML");
            System.out.println(appRootContent);*/

            JavascriptExecutor js = (JavascriptExecutor) driver;
            long lastHeight = (long) js.executeScript("return document.body.scrollHeight");
            int scrollStep = 2500;
            int maxWaitTime = 5000; // 5 секунд
            Thread.sleep(1000);

            // Прокрутка вниз
            while (true) {
                js.executeScript("window.scrollBy(0, " + scrollStep + ");");
                long newHeight = (long) js.executeScript("return document.body.scrollHeight");

                // Ждем изменения высоты страницы в течение maxWaitTime
                long startTime = System.currentTimeMillis();
                while (newHeight == lastHeight && (System.currentTimeMillis() - startTime) < maxWaitTime) {
                    Thread.sleep(500);
                    newHeight = (long) js.executeScript("return document.body.scrollHeight");
                }

                if (newHeight == lastHeight) {
                    // Если высота не изменилась за maxWaitTime, значит мы достигли конца страницы
                    break;
                }

                lastHeight = newHeight;
            }

            // Прокрутка вверх
            scrollStep = 1000;
            while (true) {
                // Прокручиваем вверх
                js.executeScript("window.scrollBy(0, -" + scrollStep + ");");

                // Ждем немного, чтобы дать время на загрузку
                Thread.sleep(500);

                // Проверяем текущее положение скролла
                long currentScrollPosition = (long) js.executeScript("return window.scrollY");
                if (currentScrollPosition == 0) {
                    break; // Достигли верха страницы
                }
            }

            System.out.println("Page: " + page);
            List<WebElement> elementVacancies = driver.findElements(By.className("santa--mb-20"));
            //System.out.println("Total number of vacancies: " + elementVacancies.size());
            elementVacanciesSize = elementVacancies.size();
            System.out.println("elementVacanciesSize: " + elementVacanciesSize);
            if (elementVacanciesSize == 0) {
                //для Railway
                countFromRemoteDriver++;
                System.out.println("countFromRemoteDriver: " + countFromRemoteDriver);
                if (countFromRemoteDriver < 3) {
                    driver.quit();
                    vacancies = getVacanciesBySilenium(position, maxVacancies, page);
                    return vacancies;
                }

                driver.quit();
                return vacancies;
            }

            System.out.println("countFromRemoteDriver: " + countFromRemoteDriver);
            countFromRemoteDriver = 0;
            System.out.println("-------------------------");
            if (elementVacancies.size() == 0) return null;
            for (WebElement elementVacancy : elementVacancies) {
                try {
                    Vacancy vacancy = new Vacancy();
                    WebElement title = elementVacancy.findElement(By.tagName("h2"));
                    vacancy.setTitle(title.getText().trim());
                    List<WebElement> spans = elementVacancy.findElements(By.cssSelector("span[_ngcontent-app-desktop-c97]"));
                    int spansSize = spans.size();
                    //System.out.println("spansSize : " + spansSize);
                    if (spansSize == 3) {
                        vacancy.setCompanyName(spans.get(0).getText().trim());
                        vacancy.setSalary("");
                        vacancy.setCity(spans.get(1).getText().trim());
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c97]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                        vacancy.setSiteName("https://robota.ua/");
                        printVacancy(vacancy);
                        vacancies.add(vacancy);
                        if (maxVacancies != null) {
                            countRecordedVacancies++;
                        }
                        if (maxVacancies != null) {
                            if (countRecordedVacancies == maxVacancies) {
                                break;
                            }
                        }
                        continue;
                    }
                    if (spansSize == 4) {
                        String salaryOrCompanyName = spans.get(0).getText().trim();
                        if (salaryOrCompanyName.matches("\\D+")) {
                            vacancy.setCompanyName(salaryOrCompanyName);
                            vacancy.setSalary("");
                            String strCity = spans.get(1).getText().trim();
                            if (strCity.matches(".*(Киев).*") || strCity.matches(".*(Київ).*")) {
                                vacancy.setCity(strCity);
                            } else {
                                vacancy.setCity(spans.get(2).getText().trim());
                            }
                        } else {
                            String strCity = spans.get(2).getText().trim();
                            if (strCity.matches(".*(Киев).*") || strCity.matches(".*(Київ).*")) {
                                vacancy.setCompanyName(spans.get(1).getText().trim());
                                vacancy.setSalary(salaryOrCompanyName);
                                vacancy.setCity(strCity);
                            } else {
                                vacancy.setCompanyName(spans.get(0).getText().trim());
                                vacancy.setSalary(spans.get(2).getText().trim());
                                vacancy.setCity(spans.get(1).getText().trim());
                            }
                        }
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c97]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                        vacancy.setSiteName("https://robota.ua/");
                        printVacancy(vacancy);
                        vacancies.add(vacancy);
                        if (maxVacancies != null) {
                            countRecordedVacancies++;
                        }
                        if (maxVacancies != null) {
                            if (countRecordedVacancies == maxVacancies) {
                                break;
                            }
                        }
                        continue;
                    }
                    if (spansSize == 5) {
                        String salaryOrCompanyName = spans.get(0).getText().trim();
                        if (salaryOrCompanyName.matches("\\D+")) {
                            vacancy.setCompanyName(salaryOrCompanyName);
                            vacancy.setSalary("");
                            vacancy.setCity(spans.get(2).getText().trim());
                        } else {
                            String strCity = spans.get(2).getText().trim();
                            if (strCity.matches(".*(Киев).*") || strCity.matches(".*(Київ).*")) {
                                vacancy.setCompanyName(spans.get(1).getText().trim());
                                vacancy.setSalary(salaryOrCompanyName);
                                vacancy.setCity(strCity);
                            } else {
                                vacancy.setCompanyName(spans.get(2).getText().trim());
                                vacancy.setSalary(salaryOrCompanyName);
                                vacancy.setCity(spans.get(3).getText().trim());
                            }
                        }
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c97]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                        vacancy.setSiteName("https://robota.ua/");
                        printVacancy(vacancy);
                        vacancies.add(vacancy);
                        if (maxVacancies != null) {
                            countRecordedVacancies++;
                        }
                        if (maxVacancies != null) {
                            if (countRecordedVacancies == maxVacancies) {
                                break;
                            }
                        }
                        continue;
                    }
                    if (spansSize == 6) {
                        vacancy.setCompanyName(spans.get(2).getText().trim());
                        vacancy.setSalary(spans.get(0).getText().trim());
                        vacancy.setCity(spans.get(3).getText().trim());
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c97]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                        vacancy.setSiteName("https://robota.ua/");
                        printVacancy(vacancy);
                        vacancies.add(vacancy);
                        if (maxVacancies != null) {
                            countRecordedVacancies++;
                        }
                        if (maxVacancies != null) {
                            if (countRecordedVacancies == maxVacancies) {
                                break;
                            }
                        }
                        continue;
                    }
                } catch (Exception e) {
                    System.out.println("Error in WebElement: " + e.getMessage());
                    continue;
                }
            }
            driver.quit();
            if (maxVacancies != null) {
                if (countRecordedVacancies == maxVacancies) {
                    return vacancies;
                }
            }
            if (vacancies.size() < 11 && elementVacanciesSize == 20) {
                System.out.println("Failed attempt");
                vacancies = getVacanciesBySilenium(position, maxVacancies, page);
                return vacancies;
            }
        } catch (Exception e) {
            driver.quit();
            System.out.println("Error: " + e.getMessage());
            System.out.println("Failed attempt");
            vacancies = getVacanciesBySilenium(position, maxVacancies, page);
            return vacancies;
        }
        return vacancies;
    }

    private List<Vacancy> getVacanciesBySileniumWithParam(Language language, City city, String position,
                                                          TimeDate time,  Integer maxVacancies,
                                                          int page) {
        List<Vacancy> vacancies = new ArrayList<>();
        WebDriver driver = null;
        RabotaUaStrategy.LanguageRabotaUa languageRabotaUa = null;
        RabotaUaStrategy.CityRabotaUa cityRabotaUa = null;
        switch (language) {
            case RUSSIAN -> languageRabotaUa = RabotaUaStrategy.LanguageRabotaUa.RUSSIAN;
            case UKRAINIAN -> languageRabotaUa = RabotaUaStrategy.LanguageRabotaUa.UKRAINIAN;
            default -> languageRabotaUa = RabotaUaStrategy.LanguageRabotaUa.RUSSIAN;
        }
        switch (city) {
            case ODESSA -> cityRabotaUa = RabotaUaStrategy.CityRabotaUa.Odessa;
            case KHARKOV -> cityRabotaUa = RabotaUaStrategy.CityRabotaUa.Kharkov;
            case DNEPROPETROVSK -> cityRabotaUa = RabotaUaStrategy.CityRabotaUa.Dnepropetrovsk;
            default -> cityRabotaUa = RabotaUaStrategy.CityRabotaUa.Kiev;
        }

        try {
            String url = String.format(URL_FORMAT_DIAPASON_TIME,
                    languageRabotaUa.getStr(), position, cityRabotaUa.getStr(), page);

            System.out.println("Initializing WebDriver...");
            //Для Railway
            String remoteUrl = "https://standalone-chrome-production-5dca.up.railway.app/wd/hub"; // Замените на ваш URL
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless"); // Запуск без графического интерфейса
            options.addArguments("--disable-gpu");
            String langDriver = "";
            switch (language) {
                case ENGLISH -> langDriver = "en";
                case UKRAINIAN -> langDriver = "uk";
                default -> langDriver = "ru";
            }
            options.addArguments("--lang=" + langDriver); // Установка языка в зависимости от параметра, например, "ru","en" или uk
            driver = new RemoteWebDriver(new URL(remoteUrl), options);

            //Для локальной работы
            //driver = new ChromeDriver();
            System.out.println("WebDriver initialized successfully.");

            driver.manage().timeouts().implicitlyWait(3, TimeUnit.SECONDS);
            driver.get(url);

            /*WebElement appRootElement = driver.findElement(By.cssSelector("app-root"));
            String appRootContent = appRootElement.getAttribute("outerHTML");
            System.out.println(appRootContent);*/

            JavascriptExecutor js = (JavascriptExecutor) driver;
            long lastHeight = (long) js.executeScript("return document.body.scrollHeight");
            int scrollStep = 2500;
            int maxWaitTime = 5000; // 5 секунд
            Thread.sleep(1000);

            // Прокрутка вниз
            while (true) {
                js.executeScript("window.scrollBy(0, " + scrollStep + ");");
                long newHeight = (long) js.executeScript("return document.body.scrollHeight");

                // Ждем изменения высоты страницы в течение maxWaitTime
                long startTime = System.currentTimeMillis();
                while (newHeight == lastHeight && (System.currentTimeMillis() - startTime) < maxWaitTime) {
                    Thread.sleep(300);
                    newHeight = (long) js.executeScript("return document.body.scrollHeight");
                }

                if (newHeight == lastHeight) {
                    // Если высота не изменилась за maxWaitTime, значит мы достигли конца страницы
                    break;
                }

                lastHeight = newHeight;
            }

            // Прокрутка вверх
            scrollStep = 1000;
            while (true) {
                // Прокручиваем вверх
                js.executeScript("window.scrollBy(0, -" + scrollStep + ");");

                // Ждем немного, чтобы дать время на загрузку
                Thread.sleep(300);

                // Проверяем текущее положение скролла
                long currentScrollPosition = (long) js.executeScript("return window.scrollY");
                if (currentScrollPosition == 0) {
                    break; // Достигли верха страницы
                }
            }

            System.out.println("Page: " + page);
            List<WebElement> elementVacancies = driver.findElements(By.className("santa--mb-20"));
            //System.out.println("Total number of vacancies: " + elementVacancies.size());
            int elementVacanciesSize = elementVacancies.size();
            if (elementVacanciesSize == 0) {
                //для Railway
                countFromRemoteDriver++;
                System.out.println("countFromRemoteDriver: " + countFromRemoteDriver);
                if (countFromRemoteDriver < 3) {
                    driver.quit();
                    vacancies = getVacanciesBySileniumWithParam(language, city, position, time,
                            maxVacancies, page);
                    return vacancies;
                }

                driver.quit();
                return vacancies;
            }

            System.out.println("countFromRemoteDriver: " + countFromRemoteDriver);
            countFromRemoteDriver = 0;
            System.out.println("-------------------------");
            for (WebElement elementVacancy : elementVacancies) {
                try {
                    Vacancy vacancy = new Vacancy();
                    WebElement title = elementVacancy.findElement(By.tagName("h2"));
                    vacancy.setTitle(title.getText().trim());
                    List<WebElement> spans = elementVacancy.findElements(By.cssSelector("span[_ngcontent-app-desktop-c97]"));
                    int spansSize = spans.size();
                    //System.out.println("spansSize : " + spansSize);
                    if (spansSize == 3) {
                        vacancy.setCompanyName(spans.get(0).getText().trim());
                        vacancy.setSalary("");
                        vacancy.setCity(spans.get(1).getText().trim());
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c97]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                        vacancy.setSiteName("https://robota.ua/");
                        printVacancy(vacancy);
                        vacancies.add(vacancy);
                        if (maxVacancies != null) {
                            countRecordedVacancies++;
                        }
                        if (maxVacancies != null) {
                            if (countRecordedVacancies == maxVacancies) {
                                break;
                            }
                        }
                        continue;
                    }
                    if (spansSize == 4) {
                        String salaryOrCompanyName = spans.get(0).getText().trim();
                        if (salaryOrCompanyName.matches("\\D+")) {
                            vacancy.setCompanyName(salaryOrCompanyName);
                            vacancy.setSalary("");
                            String strCity = spans.get(1).getText().trim();
                            if (strCity.matches(".*(" + cityRabotaUa.getNameRus() + ").*") || strCity.matches(".*(" + cityRabotaUa.nameUkr + ").*")) {
                                vacancy.setCity(strCity);
                            } else {
                                vacancy.setCity(spans.get(2).getText().trim());
                            }
                        } else {
                            String strCity = spans.get(2).getText().trim();
                            if (strCity.matches(".*(" + cityRabotaUa.getNameRus() + ").*") || strCity.matches(".*(" + cityRabotaUa.nameUkr + ").*")) {
                                vacancy.setCompanyName(spans.get(1).getText().trim());
                                vacancy.setSalary(salaryOrCompanyName);
                                vacancy.setCity(strCity);
                            } else {
                                vacancy.setCompanyName(spans.get(0).getText().trim());
                                vacancy.setSalary(spans.get(2).getText().trim());
                                vacancy.setCity(spans.get(1).getText().trim());
                            }
                        }
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c97]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                        vacancy.setSiteName("https://robota.ua/");
                        printVacancy(vacancy);
                        vacancies.add(vacancy);
                        if (maxVacancies != null) {
                            countRecordedVacancies++;
                        }
                        if (maxVacancies != null) {
                            if (countRecordedVacancies == maxVacancies) {
                                break;
                            }
                        }
                        continue;
                    }
                    if (spansSize == 5) {
                        String salaryOrCompanyName = spans.get(0).getText().trim();
                        if (salaryOrCompanyName.matches("\\D+")) {
                            vacancy.setCompanyName(salaryOrCompanyName);
                            vacancy.setSalary("");
                            vacancy.setCity(spans.get(2).getText().trim());
                        } else {
                            String strCity = spans.get(2).getText().trim();
                            if (strCity.matches(".*(" + cityRabotaUa.getNameRus() + ").*") || strCity.matches(".*(" + cityRabotaUa.nameUkr + ").*")) {
                                vacancy.setCompanyName(spans.get(1).getText().trim());
                                vacancy.setSalary(salaryOrCompanyName);
                                vacancy.setCity(strCity);
                            } else {
                                vacancy.setCompanyName(spans.get(2).getText().trim());
                                vacancy.setSalary(salaryOrCompanyName);
                                vacancy.setCity(spans.get(3).getText().trim());
                            }
                        }
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c97]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                        vacancy.setSiteName("https://robota.ua/");
                        printVacancy(vacancy);
                        vacancies.add(vacancy);
                        if (maxVacancies != null) {
                            countRecordedVacancies++;
                        }
                        if (maxVacancies != null) {
                            if (countRecordedVacancies == maxVacancies) {
                                break;
                            }
                        }
                        continue;
                    }
                    if (spansSize == 6) {
                        vacancy.setCompanyName(spans.get(2).getText().trim());
                        vacancy.setSalary(spans.get(0).getText().trim());
                        vacancy.setCity(spans.get(3).getText().trim());
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c97]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                        vacancy.setSiteName("https://robota.ua/");
                        printVacancy(vacancy);
                        vacancies.add(vacancy);
                        if (maxVacancies != null) {
                            countRecordedVacancies++;
                        }
                        if (maxVacancies != null) {
                            if (countRecordedVacancies == maxVacancies) {
                                break;
                            }
                        }
                        continue;
                    }
                } catch (Exception e) {
                    System.out.println("Error in WebElement: " + e.getMessage());
                    continue;
                }
            }
            driver.quit();
            if (maxVacancies != null) {
                if (countRecordedVacancies == maxVacancies) {
                    return vacancies;
                }
            }
            if (vacancies.size() < 11 && elementVacanciesSize == 20) {
                System.out.println("Failed attempt");
                vacancies = getVacanciesBySileniumWithParam(language, city, position, time,
                        maxVacancies, page);
                return vacancies;
            }
        } catch (Exception e) {
            driver.quit();
            System.out.println("Error: " + e.getMessage());
            System.out.println("Failed attempt");
            vacancies = getVacanciesBySileniumWithParam(language, city, position, time,
                    maxVacancies, page);
            return vacancies;
        }
        return vacancies;
    }

    private void printVacancy(Vacancy vacancy) {
        System.out.println(vacancy.getTitle());
        System.out.println(vacancy.getCompanyName());
        System.out.println(vacancy.getSalary());
        System.out.println(vacancy.getCity());
        System.out.println(vacancy.getUrl());
        System.out.println(vacancy.getSiteName());
        //System.out.println();
        System.out.println("-------------------------");
    }

    public enum LanguageRabotaUa {
        UKRAINIAN (""),
        RUSSIAN ("ru");

        private String str;

        LanguageRabotaUa (String str) {
            this.str = str;
        }

        public String getStr() {
            return str;
        }
    }

    public enum CityRabotaUa {
        Kiev ("kyiv", "Киев", "Київ"),
        Dnepropetrovsk ("dnipro", "Днепр", "Дніпро"),
        Kharkov ("kharkiv", "Харьков", "Харків"),
        Odessa("odessa", "Одесса", "Одеса");

        private String str;
        private String nameRus;
        private String nameUkr;

        CityRabotaUa (String str, String nameRus, String nameUkr) {
            this.str = str;
            this.nameRus = nameRus;
            this.nameUkr = nameUkr;
        }

        public String getStr() {
            return str;
        }

        public String getNameRus() {
            return nameRus;
        }

        public String getNameUkr() {
            return nameUkr;
        }
    }
}
