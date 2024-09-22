package com.example.parsing_vacancies.controller;

import com.example.parsing_vacancies.model.Vacancy;
import com.example.parsing_vacancies.model.resume.Education;
import com.example.parsing_vacancies.model.resume.Resume;
import com.example.parsing_vacancies.model.resume.WorkExperience;
import com.example.parsing_vacancies.repo.VacancyRepository;
import com.example.parsing_vacancies.service.OpenAIService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Controller
public class ResumeController {
    @Autowired
    private OpenAIService openAIService;
    @Autowired
    private VacancyRepository vacancyRepository;
    private static String responceTimeVar = "---\n" +
            "\n" +
            "**Резюме**\n" +
            "\n" +
            "**Алишер Балибурды Магоммедов**\n" +
            "\n" +
            "---\n" +
            "\n" +
            "**Контактная информация:**\n" +
            "- **Телефон:** +38 (077) 127 99 00\n" +
            "- **Электронная почта:** ijijij7070@gmail.com\n" +
            "- **Город:** Киев\n" +
            "\n" +
            "---\n" +
            "\n" +
            "**Цель поиска работы:**\n" +
            "Рост, развитие и карьера.\n" +
            "\n" +
            "---\n" +
            "\n" +
            "**Образование:**\n" +
            "\n" +
            "- **Киевский Политехнический Институт**\n" +
            "  - **Специальность:** Автоматика\n" +
            "  - **Годы обучения:** 1995-2001\n" +
            "\n" +
            "- **КИСИ (Киевский Институт Строительства и Архитектуры)**\n" +
            "  - **Специальность:** Экономика\n" +
            "  - **Годы обучения:** 2002-2007\n" +
            "\n" +
            "---\n" +
            "\n" +
            "**Опыт работы:**\n" +
            "\n" +
            "- **РомТорг**\n" +
            "  - **Должность:** Менеджер\n" +
            "  - **Период работы:** 2007-2010\n" +
            "  - **Обязанности:**\n" +
            "    - Управление продажами и взаимодействие с клиентами\n" +
            "    - Разработка и внедрение стратегий продаж\n" +
            "\n" +
            "- **ЧатырЕнерго**\n" +
            "  - **Должность:** Инженер\n" +
            "  - **Период работы:** 2010-2015\n" +
            "  - **Обязанности:**\n" +
            "    - Проектирование и сборка шкафов автоматики для технологических процессов\n" +
            "    - Поддержка существующих систем автоматизации\n" +
            "\n" +
            "- **Google**\n" +
            "  - **Должность:** Программист\n" +
            "  - **Период работы:** 2015-2023\n" +
            "  - **Обязанности:**\n" +
            "    - Разработка и поддержка программного обеспечения\n" +
            "    - Работа с различными языками программирования и базами данных\n" +
            "    - Участие в проектах по разработке ИИ\n" +
            "\n" +
            "---\n" +
            "\n" +
            "**Навыки и способности:**\n" +
            "\n" +
            "- **Технические навыки:**\n" +
            "  - Опыт проектирования и сборки шкафов автоматики для технологических процессов, а также продаж\n" +
            "  - Знания и опыт работы с языками программирования: Java, JavaScript, Python, C++, C#, Kotlin, HTML\n" +
            "  - Опыт работы с SQL и базами данных\n" +
            "  - Работа со множеством библиотек и фреймворков\n" +
            "\n" +
            "- **Мягкие навыки:**\n" +
            "  - Способность развиваться и быстро решать задачи\n" +
            "  - Отличные коммуникативные способности\n" +
            "  - Умение работать в команде и самостоятельность в выполнении задач\n" +
            "\n" +
            "---\n" +
            "\n" +
            "**Языки:**\n" +
            "- Английский - Продвинутый\n" +
            "- Французский - Продвинутый\n" +
            "- Немецкий - Средний\n" +
            "- Фарси - Средний\n" +
            "- Русский - Родной\n" +
            "\n" +
            "---\n" +
            "\n" +
            "**Личные достижения и награды:**\n" +
            "- Золотая медаль на олимпиаде 2017 года по разработке ИИ в Евразии.\n" +
            "\n" +
            "---\n" +
            "\n" +
            "**Дополнительные сведения:**\n" +
            "\n" +
            "- **Участие в проектах:**\n" +
            "  - Участие в международных проектах по разработке программного обеспечения и систем автоматизации\n" +
            "  - Руководство небольшими командами разработчиков\n" +
            "  - Участие в конференциях и семинарах по новым технологиям и разработке ПО\n" +
            "\n" +
            "- **Личные качества:**\n" +
            "  - Высокая мотивация и целеустремленность\n" +
            "  - Ответственность и внимание к деталям\n" +
            "  - Гибкость и адаптивность в условиях быстро меняющейся среды\n" +
            "\n" +
            "- **Интересы и хобби:**\n" +
            "  - Искусственный интеллект и машинное обучение\n" +
            "  - Чтение литературы по новейшим технологиям\n" +
            "  - Занятия спортом\n" +
            "\n" +
            "---\n" +
            "\n" +
            "**Ссылки на профессиональные профили:**\n" +
            "- LinkedIn: [Ваш профиль LinkedIn]\n" +
            "- GitHub: [Ваш профиль GitHub]\n" +
            "\n" +
            "---\n" +
            "\n" +
            "**Примечание:** Готов предоставить рекомендации по запросу.\n" +
            "\n" +
            "---\n" +
            "\n" +
            "С уважением,  \n" +
            "Алишер Балибурды Магоммедов\n" +
            "\n" +
            "---\n";

    @GetMapping("/convenient_website777/createResume")
    public String createResume(@ModelAttribute Resume resume, @RequestParam("vacancyId") Integer vacancyId, Model model) {
        // Здесь вы можете обработать данные, например, сохранить в базу данных
        model.addAttribute("title", "Формирование резюме");
        model.addAttribute("resume", resume);
        model.addAttribute("vacancyId", vacancyId);
        System.out.println(vacancyId);
        return "createResume"; // Название представления для подтверждения
    }

    @PostMapping("/convenient_website777/readyResume")
    public String readyResume(@ModelAttribute Resume resume, @RequestParam("vacancyId") Integer vacancyId,
                              @RequestParam(name = "enableAI", required = false) boolean isChatGpt, Model model) {
        // Здесь вы можете обработать данные, например, сохранить в базу данных
        model.addAttribute("title", "Готовое резюме");
        model.addAttribute("vacancyId", vacancyId);
        model.addAttribute("resume", resume);
        Optional<Vacancy> vacancyOpt = vacancyRepository.findById(vacancyId);
        ArrayList<Vacancy> res = new ArrayList<>();
        vacancyOpt.ifPresent(res::add);
        Vacancy vacancy = res.get(0);

        StringBuilder sbEducation = new StringBuilder();
        StringBuilder sbWorkExperience = new StringBuilder();
        for (int i = 0; i < resume.getEducationList().size(); i++) {
            String institutionName = resume.getEducationList().get(i).getInstitutionName();
            String specialization = resume.getEducationList().get(i).getSpecialization();
            String years = resume.getEducationList().get(i).getYears();
            String result = String.format("учебное заведение №%d - %s," +
                    " специальность учебного заведения №%d - %s," +
                    " годы обучения учебного заведения №%d - %s", i + 1, institutionName,
                    i + 1, specialization, i + 1, years);
            if (i != resume.getEducationList().size() - 1) {
               sbEducation.append(result).append("; ");
            } else {
                sbEducation.append(result).append(".");
            }
        }

        for (int i = 0; i < resume.getWorkExperienceList().size(); i++) {
            String companyName = resume.getWorkExperienceList().get(i).getCompanyName();
            String position = resume.getWorkExperienceList().get(i).getPosition();
            String period = resume.getWorkExperienceList().get(i).getPeriod();
            String result = String.format("компания №%d - %s," +
                            " должность компании №%d - %s," +
                            " период работы в компании №%d - %s", i + 1, companyName,
                    i + 1, position, i + 1, period);
            if (i != resume.getWorkExperienceList().size() - 1) {
                sbWorkExperience.append(result).append("; ");
            } else {
                sbWorkExperience.append(result).append(".");
            }
        }

        String finalResultQuery = String.format("Привет, создай мне резюме для вакансии %s," +
                " компании %s, города %s. Мои данные: ФИО - %s, номер телефона - %s, город - %s" +
                " электронная почта - %s, цель поиска работы - %s," +
                " образование {%s}, опыт работы {%s}, " +
                " я владею такими языками - %s, мои навыки и способности - %s," +
                " мои личные достижения и награды - %s. Пусть в таком порядке будет:" +
                        " Контактная информация, Цель, Образование, Опыт работы," +
                        " Навыки и способности, Владение языками," +
                        " Личные достижения и награды." +
                        " Пусть резюме начинается и завершается строкой \"---\"." +
                        " Добавь дополнительное содержимое (пусть после ключевого слова" +
                        " Дополнительная информация) для улучшения." +
                        " Перед названиями учебного заведения и названией компании работы" +
                        " пусть всегда будет пустая строка." +
                        " Резюме можно улучшить не нарушая структуру описанную.",
                vacancy.getTitle(), vacancy.getCompanyName(), vacancy.getCity(), resume.getFullName(),
                resume.getPhone(), resume.getCity(), resume.getEmail(), resume.getObjective(),
                sbEducation, sbWorkExperience, resume.getLanguages(),
                resume.getSkills(), resume.getAchievements());
        System.out.println(finalResultQuery);

        String responce = "";
        String education = "";
        String workExperience = "";
        String languages = "";
        String skills = "";
        String achievements = "";
        String addition = "";
        if (isChatGpt) {
            responce = openAIService.generateCompletion(finalResultQuery);
            //responce = responceTimeVar;
            System.out.println(responce);
            education = extractEducationSection(responce);
            workExperience = extractExperienceSection(responce);
            languages = extractLanguagesSection(responce);
            skills = extractSkillsSection(responce);
            achievements = extractAchievementsSection(responce);
            addition = extractAdditionSection(responce);
        }

        // Создание документа Word
        XWPFDocument document = new XWPFDocument();

        // Заголовок "Резюме"
        addHeader(document, "Резюме");

        // Заголовок полное имя
        addTitle(document, resume.getFullName());

        // Контактная информация
        addSectionTitle(document, "Контактная информация:", true);
        addContactInfo(document, resume);

        // Цель
        addSectionTitle(document, "Цель:", true);
        addContentToDocument(resume.getObjective(), document, false);

        // Образование
        addSectionTitle(document, "Образование:", true);
        if (isChatGpt) {
            addEducationFromChatGpt(document, education);
        } else {
            addEducation(document, resume.getEducationList());
        }

        // Опыт работы
        addSectionTitle(document, "Опыт работы:", true);
        if (isChatGpt) {
            addWorkExperienceFromChatGpt(document, workExperience);
        } else {
            addWorkExperience(document, resume.getWorkExperienceList());
        }

        // Навыки и способности
        addSectionTitle(document, "Навыки и способности:", true);
        if (isChatGpt) {
            addSkillsFromChatGpt(document, skills);
        } else {
            addSkills(document, resume.getSkills());
        }

        // Владение языками
        addSectionTitle(document, "Владение языками:", true);
        if (isChatGpt) {
            if (languages.isEmpty()) {
                addLanguages(document, resume.getLanguages());
            } else {
                addLanguagesFromChatGpt(document, languages);
            }
        } else {
            addLanguages(document, resume.getLanguages());
        }

        // Личные достижения и награды
        addSectionTitle(document, "Личные достижения и награды:", true);
        if (isChatGpt) {
            addAchievementsFromChatGpt(document, achievements);
        } else {
            addAchievements(document, resume.getAchievements());
        }

        if (isChatGpt) {
            addSectionTitle(document, "Дополнительная информация:", true);
            addAdditionFromChatGpt(document, addition);
        }

        Path pathFile = Paths.get("src/main/resources/static/resumes/").toAbsolutePath();
        String fileName = "resume_" + resume.getFullName().replace(" ", "_") + ".docx";
        FileOutputStream out;
        try {
            out = new FileOutputStream(new File(pathFile.toString(), fileName));
            document.write(out);
            out.close();
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        model.addAttribute("resumeFile", fileName);

        System.out.println("Имя файла: " + fileName);

        return "readyResume"; // Название представления для подтверждения
    }

    @Scheduled(fixedRate = 60000) // Каждую минуту
    public void cleanUpOldFiles() {
        Path directoryPath = Paths.get("src/main/resources/static/resumes");
        File directory = directoryPath.toFile();
        File[] files = directory.listFiles();

        if (files != null) {
            long currentTime = System.currentTimeMillis();
            long expirationTime = TimeUnit.MINUTES.toMillis(3); // 20 минут

            for (File file : files) {
                if (currentTime - file.lastModified() > expirationTime) {
                    try {
                        Files.delete(file.toPath());
                        System.out.println("Удалён файл: " + file.getName());
                    } catch (IOException e) {
                        System.out.println("Ошибка при удалении файла: " + file.getName());
                    }
                }
            }
        }
    }

    @GetMapping("/convenient_website777/readyResume/file_resume")
    public ResponseEntity<Resource> serveFile(@RequestParam String fileName,
                                              HttpServletRequest request) {
        // Отладочный вывод для проверки URL и переменной
        System.out.println("URL запроса: " + request.getRequestURL());
        System.out.println("fileName из URL: " + fileName); // Выводим имя файла

        Path file = Paths.get("src/main/resources/static/resumes").resolve(fileName).normalize();
        System.out.println("Полный путь к файлу: " + file.toString());

        Resource resource = new FileSystemResource(file.toFile());

        if (Files.isReadable(file)) {
            System.out.println("Файл доступен для чтения.");
        } else {
            System.out.println("Файл недоступен для чтения.");
        }

        if (!resource.exists()) {
            System.out.println("Файл не найден: " + file.toString());
            return ResponseEntity.notFound().build();
        }

        try {
            String encodedFileName = URLEncoder.encode(resource.getFilename(), StandardCharsets.UTF_8.toString());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFileName + "\"")
                    .body(resource);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/convenient_website777/sent")
    public String showSentConfirmation(@RequestParam("vacancyId") Long vacancyId, Model model) {
        System.out.println("метод showSentConfirmation сработал");
        model.addAttribute("vacancyId", vacancyId);
        model.addAttribute("message", "Ваше резюме успешно отправлено!");
        return "confirmation"; // Имя вашего шаблона для отображения
    }

    private void addHeader(XWPFDocument document, String header) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontSize(24); // Размер шрифта для заголовка "Резюме"
        run.setText(header);
        //paragraph.createRun().addBreak(); // Добавляем разрыв строки
    }


    private void addTitle(XWPFDocument document, String title) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setFontSize(20); // Размер шрифта для имени
        run.setText(title);
        //paragraph.createRun().addBreak(); // Добавляем разрыв строки
    }

    private void addContactInfo(XWPFDocument document, Resume resume) {
        XWPFParagraph paragraph = document.createParagraph();
        addContactDetail(paragraph, "Телефон:", resume.getPhone());
        addContactDetail(paragraph, "Email:", resume.getEmail());
    }

    private void addContactDetail(XWPFParagraph paragraph, String label, String value) {
        XWPFRun run = paragraph.createRun();
        run.setBold(true); // Жирный шрифт для заголовка
        run.setFontSize(12); // Размер шрифта для заголовка
        run.setText("• " + label + " "); // Черный кружок перед текстом
        run.setBold(false); // Обычный шрифт для значения
        run.setFontSize(12); // Размер шрифта для значения
        run.setText(value);
        paragraph.createRun().addBreak(); // Добавляем разрыв строки
        paragraph.setSpacingAfter(0); // Убираем отступ после каждой строки
    }

    private void addSectionTitle(XWPFDocument document, String title, boolean isBold) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingAfter(0); // Устанавливаем больший отступ после заголовка
        XWPFRun run = paragraph.createRun();
        run.setBold(isBold); // Жирный шрифт
        run.setFontSize(14); // Размер шрифта для заголовка
        run.setText(title);
    }

    private void addContentToDocument(String content, XWPFDocument document, boolean withBullets) {
        // Разделяем контент на строки
        String[] lines = content.split("\n");

        for (String line : lines) {
            line = line.trim(); // Убираем лишние пробелы
            if (line.isEmpty()) continue; // Пропускаем пустые строки

            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();

            if (withBullets) {
                run.setText("• " + line); // Черный кружок перед содержимым
            } else {
                run.setText(line); // Обычный текст
                run.setFontSize(12); // Размер шрифта для обычного текста
            }
        }
    }

    private void addEducation(XWPFDocument document, List<Education> educationList) {
        int counter = 1;
        for (Education education : educationList) {
            XWPFParagraph institutionParagraph = document.createParagraph();
            institutionParagraph.setSpacingAfter(0); // Устанавливаем отступ после названия учебного заведения
            XWPFRun institutionRun = institutionParagraph.createRun();
            institutionRun.setFontSize(12); // Размер шрифта для текста
            institutionRun.setText(counter + ". " + education.getInstitutionName());
            institutionRun.setBold(true);

            XWPFParagraph specializationParagraph = document.createParagraph();
            specializationParagraph.setSpacingAfter(0); // Устанавливаем отступ после специальности
            XWPFRun specializationRun = specializationParagraph.createRun();
            specializationRun.setFontSize(12); // Размер шрифта для текста
            specializationRun.setText("  "); // Устанавливаем пробелы
            specializationRun.setText(specializationRun.getText(0) + "- Специальность: " + education.getSpecialization()); // Объединяем текст

            XWPFParagraph yearsParagraph = document.createParagraph();
            yearsParagraph.setSpacingAfter(240); // Устанавливаем больший отступ после годов обучения
            XWPFRun yearsRun = yearsParagraph.createRun();
            yearsRun.setFontSize(12); // Размер шрифта для текста
            yearsRun.setText("  ");
            yearsRun.setText(yearsRun.getText(0) + "- Годы обучения: " + education.getYears()); // Объединяем текст

            counter++;
        }
    }

    private void addEducationFromChatGpt(XWPFDocument document, String education) {
        String[] educationLines = education.split("\n");
        int i = 0;
        for (String education1 : educationLines) {
            XWPFParagraph paragraph = document.createParagraph();
            if (i != educationLines.length - 1) {
                paragraph.setSpacingAfter(0);
            }
            XWPFRun run = paragraph.createRun();
            if (education1.matches("\\d+\\.\\s?.*")) {
                run.setBold(true);
                run.setText(education1);
                run.setFontSize(12); // Размер шрифта
            } else {
                run.setText("  "); // Устанавливаем пробелы
                run.setText(run.getText(0) + education1);
                run.setFontSize(12); // Размер шрифта
            }
            i++;
        }
    }

    private void addWorkExperience(XWPFDocument document, List<WorkExperience> workExperienceList) {
        int counter = 1;
        for (WorkExperience work : workExperienceList) {
            XWPFParagraph companyParagraph = document.createParagraph();
            companyParagraph.setSpacingAfter(0); // Устанавливаем отступ после названия компании
            XWPFRun companyRun = companyParagraph.createRun();
            companyRun.setFontSize(12); // Размер шрифта для текста
            companyRun.setText(counter + ". " + work.getCompanyName());
            companyRun.setBold(true);

            XWPFParagraph positionParagraph = document.createParagraph();
            positionParagraph.setSpacingAfter(0); // Устанавливаем отступ после должности
            XWPFRun positionRun = positionParagraph.createRun();
            positionRun.setFontSize(12); // Размер шрифта для текста
            positionRun.setText("  "); // Устанавливаем пробелы
            positionRun.setText(positionRun.getText(0) + "- Должность: " + work.getPosition()); // Объединяем текст

            XWPFParagraph periodParagraph = document.createParagraph();
            periodParagraph.setSpacingAfter(240); // Устанавливаем больший отступ перед следующим заголовком
            XWPFRun periodRun = periodParagraph.createRun();
            periodRun.setFontSize(12); // Размер шрифта для текста
            periodRun.setText("  "); // Устанавливаем пробелы
            periodRun.setText(periodRun.getText(0) + "- Период работы: " + work.getPeriod()); // Объединяем текст

            counter++;
        }
    }

    private void addWorkExperienceFromChatGpt(XWPFDocument document, String workExperience) {
        String[] workExperienceLines = workExperience.split("\n");
        int i = 0;
        for (String experience : workExperienceLines) {
            XWPFParagraph paragraph = document.createParagraph();
            if (i != workExperienceLines.length - 1) {
                paragraph.setSpacingAfter(0);
            }
            XWPFRun run = paragraph.createRun();
            if (experience.matches("\\d+\\.\\s?.*")) {
                run.setBold(true);
                run.setText(experience);
                run.setFontSize(12); // Размер шрифта
            } else {
                run.setText("  "); // Устанавливаем пробелы
                run.setText(run.getText(0) + experience);
                run.setFontSize(12); // Размер шрифта
            }
            i++;
        }
    }

    private void addSkills(XWPFDocument document, String skills) {
        String[] skillLines = skills.split("\n");
        for (String skill : skillLines) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(skill.trim());
            run.setFontSize(12); // Размер шрифта для навыков
        }
    }

    private void addSkillsFromChatGpt(XWPFDocument document, String skills) {
        String[] skillLines = skills.split("\n");
        int i = 0;
        for (String skill : skillLines) {
            XWPFParagraph paragraph = document.createParagraph();
            if (i != skillLines.length - 1) {
                paragraph.setSpacingAfter(0);
            }
            XWPFRun run = paragraph.createRun();
            run.setText(skill.trim());
            run.setFontSize(12); // Размер шрифта для навыков
            i++;
        }
    }

    private void addLanguages(XWPFDocument document, String languages) {
        String[] languageLines = languages.split(","); // Предполагаем, что языки разделены запятыми
        int i = 0;
        for (String language : languageLines) {
            XWPFParagraph paragraph = document.createParagraph();
            if (i != languageLines.length - 1) {
                paragraph.setSpacingAfter(0);
            }
            XWPFRun run = paragraph.createRun();
            run.setText("• " + language.trim());
            run.setFontSize(12); // Размер шрифта для языков
            i++;
        }
    }

    private void addLanguagesFromChatGpt(XWPFDocument document, String languages) {
        String[] languageLines = languages.split("\n");
        int i = 0;
        for (String language : languageLines) {
            XWPFParagraph paragraph = document.createParagraph();
            if (i != languageLines.length - 1) {
                paragraph.setSpacingAfter(0);
            }
            XWPFRun run = paragraph.createRun();
            run.setText(language.trim());
            run.setFontSize(12); // Размер шрифта для навыков
            i++;
        }
    }

    private void addAchievements(XWPFDocument document, String achievements) {
        String[] achievementLines = achievements.split("\n");
        for (String achievement : achievementLines) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText(achievement.trim());
            run.setFontSize(12); // Размер шрифта для достижений
        }
    }

    private void addAchievementsFromChatGpt(XWPFDocument document, String achievements) {
        String[] achievementsLines = achievements.split("\n");
        int i = 0;
        for (String achievement : achievementsLines) {
            XWPFParagraph paragraph = document.createParagraph();
            if (i != achievementsLines.length - 1) {
                paragraph.setSpacingAfter(0);
            }
            XWPFRun run = paragraph.createRun();
            run.setText(achievement.trim());
            run.setFontSize(12); // Размер шрифта для навыков
            i++;
        }
    }

    private void addAdditionFromChatGpt(XWPFDocument document, String addition) {
        String[] additionLines = addition.split("\n");
        int i = 0;
        int varAfterColon = 0;
        int countTextColon = 0;
        for (String addition1 : additionLines) {
            if (varAfterColon == 2) {
                if (addition1.isEmpty()) {
                    continue;
                }
            }
            XWPFParagraph paragraph = document.createParagraph();
            if (i != additionLines.length - 1) {
                paragraph.setSpacingAfter(0);
            }
            XWPFRun run = paragraph.createRun();
            if ((addition1.matches(".+(:)") && countTextColon == 0)
                    || addition1.contains("Дополнительная информация")) {
                run.setBold(true);
                run.setText(addition1.trim());
                run.setFontSize(14);
                varAfterColon = 1;
                countTextColon++;
                varAfterColon++;
                continue;
            }
            varAfterColon++;
            run.setText(addition1.trim());
            run.setFontSize(12); // Размер шрифта для навыков
            i++;
        }
    }

    private static String extractEducationSection(String resume) {
        String[] sections = resume.split("Образование");
        String[] lines = sections[1].split("\n");
        StringBuilder sbEducation = new StringBuilder();

        for (String line : lines) {
            if (line.contains("Опыт работы"))
                break;
            line = line.trim();
            line = line.replaceAll("\\*+", ""); // Удаление выделения

            if (!(line.length() < 4) || line.isEmpty()) {
                sbEducation.append(line).append("\n");
            }
        }
        String education = refinementEducationOrExperience(sbEducation.toString());
        return education.trim();
    }

    private static String extractExperienceSection(String resume) {
        String[] sections = resume.split("Опыт работы");
        String[] lines = sections[1].split("\n");
        StringBuilder sbExperience = new StringBuilder();

        for (String line : lines) {
            if (line.contains("Навыки и способности"))
                break;
            line = line.trim();
            line = line.replaceAll("\\*+", ""); // Удаление выделения

            if (!(line.length() < 4) || line.isEmpty()) {
                sbExperience.append(line).append("\n");
            }
        }
        String experience = refinementEducationOrExperience(sbExperience.toString());
        return experience.trim();
    }

    private static String refinementEducationOrExperience(String educationOrExperience) {
        String[] lines = educationOrExperience.split("\n");
        StringBuilder sbExperience = new StringBuilder();
        int countAfterEmpty = 0;
        int count = 1;
        boolean isAfterColon = false;
        boolean isNumberWithPoint = false;
        for (String line : lines) {
            line = line.replaceAll("\\#+", "");
            if (line.contains("---")) {
                continue;
            }
            if (!line.isEmpty()) {
                countAfterEmpty++;
                isNumberWithPoint = false;
            } else {
                if (isNumberWithPoint) {
                    isNumberWithPoint = false;
                    continue;
                } else {
                    countAfterEmpty = 0;
                    isAfterColon = false;
                }
            }
            if (countAfterEmpty == 1) {
                if (!line.matches("\\d+\\.\\s?.*")) {
                    if (line.contains("- ")) {
                        line = line.replaceFirst("- ", count + ". ");
                    } else {
                        line = count + ". " + line;
                    }
                }
                count++;
            }
            if (line.matches("\\d+\\.\\s?.*")) {
                isNumberWithPoint = true;
            }
            if (isAfterColon) {
                line = " " + line;
            }

            sbExperience.append(line).append("\n");

            if (line.endsWith(":")) {
                isAfterColon = true;
            }
        }
        return sbExperience.toString();
    }

    private static String extractSkillsSection(String resume) {
        String[] sections = resume.split("Навыки и способности");
        String[] lines = sections[1].split("\n");
        StringBuilder skills = new StringBuilder();

        for (String line : lines) {
            if (line.contains("Владение языками"))
                break;
            if (line.contains("---")) {
                continue;
            }
            line = line.trim();
            if (line.startsWith("- ") && !line.endsWith(":**")) {
                line = line.replaceFirst("- ", "• ");
            }
            line = line.replace("**", ""); // Удаление выделения
            if (!(line.length() < 4)) {
                skills.append(line).append("\n");
            }
        }
        return skills.toString().trim();
    }

    private static String extractLanguagesSection(String resume) {
        String[] sections = null;
        try {
            sections = resume.split("Владение языками");
        } catch (Exception e) {
            return "";
        }
        String[] lines = sections[1].split("\n");
        StringBuilder skills = new StringBuilder();

        for (String line : lines) {
            if (line.contains("Личные достижения и награды"))
                break;
            if (line.contains("---")) {
                continue;
            }
            line = line.trim();
            if (line.startsWith("- ") && !line.endsWith(":**")) {
                line = line.replaceFirst("- ", "• ");
            }
            line = line.replace("**", ""); // Удаление выделения
            if (!(line.length() < 4)) {
                skills.append(line).append("\n");
            }
        }
        return skills.toString().trim();
    }

    private static String extractAchievementsSection(String resume) {
        String[] sections = resume.split("Личные достижения и награды");
        String[] lines = sections[1].split("\n");
        StringBuilder achievements = new StringBuilder();

        for (String line : lines) {
            if (line.contains("Дополнительная информация"))
                break;
            if (line.contains("---")) {
                continue;
            }
            line = line.trim();
            line = line.replace("**", ""); // Удаление выделения
            if (!(line.length() < 4)) {
                achievements.append(line).append("\n");
            }
        }
        return achievements.toString().trim();
    }

    private static String extractAdditionSection(String resume) {
        String[] sections = resume.split("Дополнительная информация");
        String[] lines = sections[1].split("\n");
        StringBuilder sbAddition = new StringBuilder();

        for (String line : lines) {
            if (line.contains("---")) {
                continue;
            }
            line = line.trim();
            line = line.replace("**", ""); // Удаление выделения
            line = line.replaceAll("\\#+", "");

            if (!(line.length() < 4) || line.isEmpty()) {
                sbAddition.append(line).append("\n");
            }
            if (line.contains("---"))
                break;
        }
        String addition = refinementAddition(sbAddition.toString());
        return addition.trim();
    }

    private static String refinementAddition(String addition) {
        String[] lines = addition.split("\n");
        StringBuilder sbExperience = new StringBuilder();
        int countAfterEmpty = 0;
        boolean isAfterColon = false;
        for (String line : lines) {
            if (line.contains("---")) {
                continue;
            }
            if (!line.isEmpty()) {
                countAfterEmpty++;
            } else {
                countAfterEmpty = 0;
                isAfterColon = false;
            }

            if (isAfterColon) {
                line = "  " + line;
            }

            sbExperience.append(line).append("\n");

            if (line.endsWith(":")) {
                isAfterColon = true;
            }
        }
        return sbExperience.toString();
    }

    public static void main(String[] args) {
        // Поиск раздела "Навыки и способности"
        //String education = extractEducationSection(ResumeController.responceTimeVar);
        //String experience = extractExperienceSection(ResumeController.responceTimeVar);
        //String languages = extractLanguagesSection(ResumeController.responceTimeVar);
        //String skillsSection = extractSkillsSection(ResumeController.responceTimeVar);
        //String achievements = extractAchievementsSection(ResumeController.responceTimeVar);
        String addition = extractAdditionSection(ResumeController.responceTimeVar);
        System.out.println(addition);
    }
}
