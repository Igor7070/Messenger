package com.example.unl_pos12.view.html;

import com.example.unl_pos12.controller.job_search.Controller;
import com.example.unl_pos12.model.job_search.*;
import com.example.unl_pos12.view.View;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;

public class HtmlView implements View {
    private Controller controller;
    private final String filePathWorkUa = "../Parsing_Vacancies_2.0/src/main/java/" + this.getClass().getPackage().getName().replace('.', '/') + "/vacanciesWorkUa.html";
    private final String filePathRabotaUa = "../Parsing_Vacancies_2.0/src/main/java/" + this.getClass().getPackage().getName().replace('.', '/') + "/vacanciesRabotaUa.html";
    private final String filePathFullList = "../Parsing_Vacancies_2.0/src/main/java/" + this.getClass().getPackage().getName().replace('.', '/') + "/fullList.html";

    @Override
    public void update(List<Vacancy> vacancies) {
        try {
            String resultHtml = getUpdatedFileContent(vacancies, filePathFullList);
            updateFile(resultHtml, filePathFullList);
            System.out.println("Data was successfully written to the file: fullList.html");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void update(List<Vacancy> vacancies, Provider provider) {
        try {
            Strategy strategy = provider.getStrategy();
            if (strategy instanceof WorkUaStrategy) {
                String resultHtml = getUpdatedFileContent(vacancies, filePathWorkUa);
                updateFile(resultHtml, filePathWorkUa);
                System.out.println("Data was successfully written to the file: vacanciesWorkUa.html");
            } else if (strategy instanceof RabotaUaStrategy) {
                String resultHtml = getUpdatedFileContent(vacancies, filePathRabotaUa);
                updateFile(resultHtml, filePathRabotaUa);
                System.out.println("Data was successfully written to the file: vacanciesRabotaUa.html");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String getUpdatedFileContent(List<Vacancy> vacancies, String filePath) {
        Document htmlFile = null;
        try {
            htmlFile = getDocument(filePath);

            //клонирование и сздание шаблона вакансии
            Element classTemplateElement = htmlFile.getElementsByClass("template").first();
            Element template = classTemplateElement.clone();
            template.removeAttr("style");
            template.removeClass("template");
            //удаление вакансий с тегом tr и оставление только шаблонной пустой вакансии
            Elements trTags = htmlFile.getElementsByTag("tr");
            Iterator<Element> iterElem = trTags.iterator();
            while (iterElem.hasNext()) {
                Element element = iterElem.next();
                if (element.hasClass("vacancy") && !element.hasClass("template"))
                    element.remove();
            }
            //doc.select("tr[class=vacancy]").remove(); можно было так удалить вакансии
            //перебор списка вакансий и создание (клонирование) из шаблона элемента вакансии,
            //который заполняется данными, после чего вставление в общий html перед пустой шаблонной вакансией
            for (Vacancy vacancy : vacancies) {
                Element elemVacancy = template.clone();
                elemVacancy.getElementsByClass("city").first().text(vacancy.getCity());
                elemVacancy.getElementsByClass("companyName").first().text(vacancy.getCompanyName());
                elemVacancy.getElementsByClass("salary").first().text(vacancy.getSalary());
                elemVacancy.getElementsByTag("a").first().text(vacancy.getTitle());
                elemVacancy.getElementsByTag("a").first().attr("href", vacancy.getUrl());
                classTemplateElement.before(elemVacancy.outerHtml());
            }
        } catch (Exception e) {
            /*e.printStackTrace();
            return "Some exception occurred";*/
            throw new RuntimeException(e);
        }

        return htmlFile.html();
    }

    private void updateFile(String str, String filePath) {
        Path pathToHtmlFile = Paths.get(filePath).toAbsolutePath();
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filePath)))) {
            writer.write(str);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected Document getDocument(String filePath) throws IOException {
        Document htmlFile = null;
        try {
            htmlFile = Jsoup.parse(new File(filePath), "UTF-8");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return htmlFile;
    }

    @Override
    public void setController(Controller controller) {
        this.controller = controller;
    }

    /*public static void main(String[] args) {
        List<Vacancy> vacancies = new ArrayList<>();
        Vacancy vacancy = new Vacancy();
        vacancy.setTitle("Java Developer");
        vacancy.setCompanyName("Sony");
        vacancy.setCity("Kiev");
        vacancy.setSalary("3700");
        vacancy.setSiteName("https://grc.ua");
        vacancy.setUrl("https://grc.ua/vacancies?page=1&categories=1&local_area=kyiv");
        vacancies.add(vacancy);
        System.out.println(new HtmlView().getUpdatedFileContent(vacancies));
    }*/
}
