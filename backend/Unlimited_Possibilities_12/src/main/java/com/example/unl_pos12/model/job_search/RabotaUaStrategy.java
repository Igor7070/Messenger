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
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RabotaUaStrategy implements Strategy {
    private static final String URL_FORMAT = "https://robota.ua/ru/zapros/%s/kyiv?page=%d";
    private static final String URL_FORMAT_DIAPASON_TIME =
            "https://robota.ua/%s/zapros/%s/%s?page=%d";
    private int countRecordedVacancies = 0;
    private static int countFromRemoteDriver = 0; //для удаленного WebDriver
    private static final int MAX_RETRIES = 5; // Максимальное количество рекурсивных попыток
    private int retryCount = 0; // Счетчик попыток

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

    private List<Vacancy> getVacanciesBySilenium(String position, Integer maxVacancies, int page) {
        List<Vacancy> vacancies = new ArrayList<>();
        WebDriver driver = null;

        if (retryCount >= MAX_RETRIES) {
            System.out.println("Достигнут лимит попыток: " + MAX_RETRIES);
            return vacancies;
        }

        try {
            //Для Railway
            String remoteUrl = "https://standalone-chrome-production-19d7.up.railway.app/wd/hub";

            ChromeOptions options = new ChromeOptions();

            // КРИТИЧНО ДЛЯ СЕРВЕРА (Railway, Docker и т.п.)
            options.addArguments("--headless=new");           // Новый, быстрый headless-режим
            options.addArguments("--no-sandbox");             // Обязательно в контейнерах
            options.addArguments("--disable-dev-shm-usage");  // Предотвращает краши из-за памяти
            options.addArguments("--disable-gpu");            // Отключаем GPU (не нужен в headless)
            options.addArguments("--disable-extensions");     // Ускоряет запуск
            options.addArguments("--disable-infobars");       // Убирает предупреждения
            options.addArguments("--window-size=1920,1080");  // Фиксированный размер окна
            options.addArguments("--lang=ru");                // Язык интерфейса
            options.addArguments("--remote-debugging-port=9222"); // На случай отладки

            // Опционально: если знаешь версию Chrome в контейнере — укажи
            // options.setBrowserVersion("129");

            // Создаём драйвер с таймаутами и логами
            try {
                System.out.println("Подключение к Selenium Grid: " + remoteUrl);
                driver = new RemoteWebDriver(new URL(remoteUrl), options);

                // Устанавливаем таймауты
                driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
                driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));

                System.out.println("WebDriver успешно запущен! Session ID: " +
                        ((RemoteWebDriver) driver).getSessionId());

            } catch (Exception e) {
                System.err.println("ОШИБКА: Не удалось запустить WebDriver: " + e.getMessage());
                e.printStackTrace();
                throw new RuntimeException("Сессия Selenium не создана", e);
            }

            //Для локальной работы
            //driver = new ChromeDriver();
            driver.manage().timeouts().implicitlyWait(3, TimeUnit.SECONDS);
            String url = String.format(URL_FORMAT, position, page);
            driver.get(url);
            System.out.println("Открыта страница: " + url);

            /*WebElement appRootElement = driver.findElement(By.cssSelector("app-root"));
            String appRootContent = appRootElement.getAttribute("outerHTML");
            System.out.println(appRootContent);*/

            // Прокрутка вниз до конца страницы
            JavascriptExecutor js = (JavascriptExecutor) driver;
            long lastHeight = (long) js.executeScript("return document.body.scrollHeight");
            int scrollStep = 4500;
            int maxWaitTime = 5000;

            while (true) {
                js.executeScript("window.scrollBy(0, " + scrollStep + ");");
                Thread.sleep(1500);
                long newHeight = (long) js.executeScript("return document.body.scrollHeight");
                System.out.println("Прокрутка вниз: новая высота = " + newHeight + ", старая высота = " + lastHeight);

                long startTime = System.currentTimeMillis();
                while (newHeight == lastHeight && (System.currentTimeMillis() - startTime) < maxWaitTime) {
                    Thread.sleep(500);
                    newHeight = (long) js.executeScript("return document.body.scrollHeight");
                }

                if (newHeight == lastHeight) {
                    System.out.println("Достигнут конец страницы: " + newHeight);
                    break;
                }
                lastHeight = newHeight;
            }

            // Прокрутка вверх
            scrollStep = 1000;
            while (true) {
                js.executeScript("window.scrollBy(0, -" + scrollStep + ");");
                Thread.sleep(300);
                System.out.println("Прокрутка вверх");
                long currentScrollPosition = (long) js.executeScript("return window.scrollY");
                if (currentScrollPosition <= 0) {
                    System.out.println("Достигнут верх страницы");
                    break;
                }
            }

            // Явное ожидание элементов вакансий
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            List<WebElement> elementVacancies;

            // Проверяем наличие контейнеров и извлекаем элементы santa--mb-20 внутри них
            try {
                elementVacancies = wait.until(
                        ExpectedConditions.presenceOfAllElementsLocatedBy(
                                By.cssSelector("alliance-jobseeker-desktop-vacancies-list .santa--mb-20, alliance-jobseeker-mobile-vacancies-list .santa--mb-20")
                        )
                );
            } catch (Exception e) {
                System.out.println("Контейнеры alliance-jobseeker-desktop-vacancies-list или alliance-jobseeker-mobile-vacancies-list не найдены: " + e.getMessage());
                throw new RuntimeException("Не удалось найти элементы вакансий внутри нужных контейнеров");
            }

            System.out.println("Найдено вакансий: " + elementVacancies.size());
            if (elementVacancies.isEmpty()) {
                countFromRemoteDriver++;
                System.out.println("countFromRemoteDriver: " + countFromRemoteDriver);
                if (countFromRemoteDriver < 3) {
                    driver.quit();
                    return getVacanciesBySilenium(position, maxVacancies, page);
                }
                driver.quit();
                return vacancies;
            }

            countFromRemoteDriver = 0;
            System.out.println("-------------------------");

            // Обработка вакансий
            for (WebElement elementVacancy : elementVacancies) {
                try {
                    Vacancy vacancy = new Vacancy();
                    vacancy.setTitle(elementVacancy.findElement(By.tagName("h2")).getText().trim());

                    List<WebElement> spans = elementVacancy.findElements(By.cssSelector("span[_ngcontent-app-desktop-c101]"));
                    int spansSize = spans.size();

                    if (spansSize == 3) {
                        vacancy.setCompanyName(spans.get(0).getText().trim());
                        vacancy.setSalary("");
                        vacancy.setCity(spans.get(1).getText().trim());
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c101]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                    } else if (spansSize == 4) {
                        String salaryOrCompanyName = spans.get(0).getText().trim();
                        if (salaryOrCompanyName.matches("\\D+")) {
                            vacancy.setCompanyName(salaryOrCompanyName);
                            vacancy.setSalary("");
                            String strCity = spans.get(1).getText().trim();
                            vacancy.setCity(strCity.matches(".*(Киев|Київ).*") ? strCity : spans.get(2).getText().trim());
                        } else {
                            String strCity = spans.get(2).getText().trim();
                            if (strCity.matches(".*(Киев|Київ).*")) {
                                vacancy.setCompanyName(spans.get(1).getText().trim());
                                vacancy.setSalary(salaryOrCompanyName);
                                vacancy.setCity(strCity);
                            } else {
                                vacancy.setCompanyName(spans.get(0).getText().trim());
                                vacancy.setSalary(spans.get(2).getText().trim());
                                vacancy.setCity(spans.get(1).getText().trim());
                            }
                        }
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c101]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                    } else if (spansSize == 5) {
                        String salaryOrCompanyName = spans.get(0).getText().trim();
                        if (salaryOrCompanyName.matches("\\D+")) {
                            vacancy.setCompanyName(salaryOrCompanyName);
                            vacancy.setSalary("");
                            vacancy.setCity(spans.get(2).getText().trim());
                        } else {
                            String strCity = spans.get(2).getText().trim();
                            if (strCity.matches(".*(Киев|Київ).*")) {
                                vacancy.setCompanyName(spans.get(1).getText().trim());
                                vacancy.setSalary(salaryOrCompanyName);
                                vacancy.setCity(strCity);
                            } else {
                                vacancy.setCompanyName(spans.get(2).getText().trim());
                                vacancy.setSalary(salaryOrCompanyName);
                                vacancy.setCity(spans.get(3).getText().trim());
                            }
                        }
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c101]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                    } else if (spansSize == 6) {
                        vacancy.setCompanyName(spans.get(2).getText().trim());
                        vacancy.setSalary(spans.get(0).getText().trim());
                        vacancy.setCity(spans.get(3).getText().trim());
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c101]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                    }

                    vacancy.setSiteName("https://robota.ua/");

                    printVacancy(vacancy);
                    vacancies.add(vacancy);

                    if (maxVacancies != null) {
                        countRecordedVacancies++;
                        if (countRecordedVacancies >= maxVacancies) { // Изменено на >= для надежности
                            System.out.println("Достигнуто максимальное количество вакансий: " + maxVacancies);
                            return vacancies; // Завершаем метод, как только собрано нужное количество
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Ошибка при обработке вакансии: " + e.getMessage());
                    continue;
                }
            }

        } catch (Exception e) {
            System.out.println("Ошибка: " + e.getMessage() + ". Повторная попытка " + (retryCount + 1));
            retryCount++;
            if (driver != null) {
                driver.quit();
            }
            if (retryCount < 2) {
                return getVacanciesBySilenium(position, maxVacancies, page);
            }
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }

        retryCount = 0;
        return vacancies;
    }

    private List<Vacancy> getVacanciesBySileniumWithParam(Language language, City city, String position,
                                                          TimeDate time, Integer maxVacancies, int page) {
        List<Vacancy> vacancies = new ArrayList<>();
        WebDriver driver = null;

        if (retryCount >= MAX_RETRIES) {
            System.out.println("Достигнут лимит попыток: " + MAX_RETRIES);
            return vacancies;
        }

        // Преобразование параметров
        RabotaUaStrategy.LanguageRabotaUa languageRabotaUa = switch (language) {
            case RUSSIAN -> RabotaUaStrategy.LanguageRabotaUa.RUSSIAN;
            case UKRAINIAN -> RabotaUaStrategy.LanguageRabotaUa.UKRAINIAN;
            default -> RabotaUaStrategy.LanguageRabotaUa.RUSSIAN;
        };

        RabotaUaStrategy.CityRabotaUa cityRabotaUa = switch (city) {
            case ODESSA -> RabotaUaStrategy.CityRabotaUa.Odessa;
            case KHARKOV -> RabotaUaStrategy.CityRabotaUa.Kharkov;
            case DNEPROPETROVSK -> RabotaUaStrategy.CityRabotaUa.Dnepropetrovsk;
            default -> RabotaUaStrategy.CityRabotaUa.Kiev;
        };

        try {
            String url = String.format(URL_FORMAT_DIAPASON_TIME,
                    languageRabotaUa.getStr(), position, cityRabotaUa.getStr(), page);
            System.out.println("Открыта страница: " + url);

            //Для Railway
            String remoteUrl = "https://standalone-chrome-production-4953.up.railway.app/wd/hub";
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--disable-gpu");
            String langDriver = switch (language) {
                case ENGLISH -> "en";
                case UKRAINIAN -> "uk";
                default -> "ru";
            };
            options.addArguments("--lang=" + langDriver);
            System.out.println("Initializing WebDriver...");
            driver = new RemoteWebDriver(new URL(remoteUrl), options);

            //Для локальной работы
            //driver = new ChromeDriver();

            System.out.println("WebDriver initialized successfully.");

            driver.manage().timeouts().implicitlyWait(3, TimeUnit.SECONDS);
            driver.get(url);

            /*WebElement appRootElement = driver.findElement(By.cssSelector("app-root"));
            String appRootContent = appRootElement.getAttribute("outerHTML");
            System.out.println(appRootContent);*/

            // Прокрутка вниз
            JavascriptExecutor js = (JavascriptExecutor) driver;
            long lastHeight = (long) js.executeScript("return document.body.scrollHeight");
            int scrollStep = 4500;
            int maxWaitTime = 5000;
            Thread.sleep(1500);

            while (true) {
                js.executeScript("window.scrollBy(0, " + scrollStep + ");");
                Thread.sleep(1500);
                long newHeight = (long) js.executeScript("return document.body.scrollHeight");
                System.out.println("Прокрутка вниз: новая высота = " + newHeight + ", старая высота = " + lastHeight);

                long startTime = System.currentTimeMillis();
                while (newHeight == lastHeight && (System.currentTimeMillis() - startTime) < maxWaitTime) {
                    Thread.sleep(500);
                    newHeight = (long) js.executeScript("return document.body.scrollHeight");
                }

                if (newHeight == lastHeight) {
                    System.out.println("Достигнут конец страницы: " + newHeight);
                    break;
                }
                lastHeight = newHeight;
            }

            // Прокрутка вверх
            scrollStep = 1000;
            while (true) {
                js.executeScript("window.scrollBy(0, -" + scrollStep + ");");
                Thread.sleep(300);
                System.out.println("Прокрутка вверх");
                long currentScrollPosition = (long) js.executeScript("return window.scrollY");
                if (currentScrollPosition <= 0) {
                    System.out.println("Достигнут верх страницы");
                    break;
                }
            }

            // Явное ожидание элементов вакансий
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
            List<WebElement> elementVacancies;

            // Проверяем наличие контейнеров и извлекаем элементы santa--mb-20 внутри них
            try {
                elementVacancies = wait.until(
                        ExpectedConditions.presenceOfAllElementsLocatedBy(
                                By.cssSelector("alliance-jobseeker-desktop-vacancies-list .santa--mb-20, alliance-jobseeker-mobile-vacancies-list .santa--mb-20")
                        )
                );
            } catch (Exception e) {
                System.out.println("Контейнеры alliance-jobseeker-desktop-vacancies-list или alliance-jobseeker-mobile-vacancies-list не найдены: " + e.getMessage());
                throw new RuntimeException("Не удалось найти элементы вакансий внутри нужных контейнеров");
            }

            System.out.println("Найдено вакансий: " + elementVacancies.size());
            if (elementVacancies.isEmpty()) {
                countFromRemoteDriver++;
                System.out.println("countFromRemoteDriver: " + countFromRemoteDriver);
                if (countFromRemoteDriver < 3) {
                    driver.quit();
                    return getVacanciesBySileniumWithParam(language, city, position, time, maxVacancies, page);
                }
                driver.quit();
                return vacancies;
            }

            countFromRemoteDriver = 0;
            System.out.println("-------------------------");

            // Обработка вакансий
            for (WebElement elementVacancy : elementVacancies) {
                try {
                    Vacancy vacancy = new Vacancy();
                    vacancy.setTitle(elementVacancy.findElement(By.tagName("h2")).getText().trim());

                    List<WebElement> spans = elementVacancy.findElements(By.cssSelector("span[_ngcontent-app-desktop-c101]"));
                    int spansSize = spans.size();

                    if (spansSize == 3) {
                        vacancy.setCompanyName(spans.get(0).getText().trim());
                        vacancy.setSalary("");
                        vacancy.setCity(spans.get(1).getText().trim());
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c101]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                    } else if (spansSize == 4) {
                        String salaryOrCompanyName = spans.get(0).getText().trim();
                        if (salaryOrCompanyName.matches("\\D+")) {
                            vacancy.setCompanyName(salaryOrCompanyName);
                            vacancy.setSalary("");
                            String strCity = spans.get(1).getText().trim();
                            vacancy.setCity(strCity.matches(".*(" + cityRabotaUa.getNameRus() + "|" + cityRabotaUa.getNameUkr() + ").*") ? strCity : spans.get(2).getText().trim());
                        } else {
                            String strCity = spans.get(2).getText().trim();
                            if (strCity.matches(".*(" + cityRabotaUa.getNameRus() + "|" + cityRabotaUa.getNameUkr() + ").*")) {
                                vacancy.setCompanyName(spans.get(1).getText().trim());
                                vacancy.setSalary(salaryOrCompanyName);
                                vacancy.setCity(strCity);
                            } else {
                                vacancy.setCompanyName(spans.get(0).getText().trim());
                                vacancy.setSalary(spans.get(2).getText().trim());
                                vacancy.setCity(spans.get(1).getText().trim());
                            }
                        }
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c101]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                    } else if (spansSize == 5) {
                        String salaryOrCompanyName = spans.get(0).getText().trim();
                        if (salaryOrCompanyName.matches("\\D+")) {
                            vacancy.setCompanyName(salaryOrCompanyName);
                            vacancy.setSalary("");
                            vacancy.setCity(spans.get(2).getText().trim());
                        } else {
                            String strCity = spans.get(2).getText().trim();
                            if (strCity.matches(".*(" + cityRabotaUa.getNameRus() + "|" + cityRabotaUa.getNameUkr() + ").*")) {
                                vacancy.setCompanyName(spans.get(1).getText().trim());
                                vacancy.setSalary(salaryOrCompanyName);
                                vacancy.setCity(strCity);
                            } else {
                                vacancy.setCompanyName(spans.get(2).getText().trim());
                                vacancy.setSalary(salaryOrCompanyName);
                                vacancy.setCity(spans.get(3).getText().trim());
                            }
                        }
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c101]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                    } else if (spansSize == 6) {
                        vacancy.setCompanyName(spans.get(2).getText().trim());
                        vacancy.setSalary(spans.get(0).getText().trim());
                        vacancy.setCity(spans.get(3).getText().trim());
                        String urlHref = elementVacancy.findElement(By.cssSelector("a[_ngcontent-app-desktop-c101]")).getAttribute("href").trim();
                        vacancy.setUrl(urlHref);
                    }

                    vacancy.setSiteName("https://robota.ua/");

                    printVacancy(vacancy);
                    vacancies.add(vacancy);

                    if (maxVacancies != null) {
                        countRecordedVacancies++;
                        if (countRecordedVacancies >= maxVacancies) {
                            System.out.println("Достигнуто максимальное количество вакансий: " + maxVacancies);
                            driver.quit();
                            return vacancies;
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Ошибка при обработке вакансии: " + e.getMessage());
                    continue;
                }
            }

        } catch (Exception e) {
            System.out.println("Ошибка: " + e.getMessage() + ". Повторная попытка " + (retryCount + 1));
            retryCount++;
            if (driver != null) {
                driver.quit();
            }
            if (retryCount < 2) {
                return getVacanciesBySileniumWithParam(language, city, position, time, maxVacancies, page);
            }
        } finally {
            if (driver != null) {
                driver.quit();
            }
        }

        retryCount = 0;
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
