package com.example.unl_pos12.service;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Где лежат загруженные файлы: аватары, вложения, шифрованные вложения.
 *
 * Прежде каждый обработчик писал свой путь руками: аватары уходили в
 * src/main/resources/static/avatars, вложения — в uploads рядом с запущенной
 * программой. И то и другое живёт внутри контейнера, а он у Railway
 * пересоздаётся при каждом развёртывании — поэтому после каждой выкатки
 * фотографии в переписке и аватары людей превращались в «Image unavailable».
 *
 * Теперь путь один и задаётся снаружи: переменная UPLOAD_ROOT указывает на
 * подключённый том, который выкаты переживает. Без неё всё работает
 * по-старому, рядом с программой, — так удобно запускать у себя.
 */
public final class StoragePaths {

    private static final String ENV = "UPLOAD_ROOT";

    private StoragePaths() {
    }

    /** Корень хранилища: том, если он подключён, иначе папка рядом с программой. */
    public static Path root() {
        String configured = System.getenv(ENV);
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return Paths.get(System.getProperty("user.dir", "."));
    }

    public static Path avatars() {
        return ensure(root().resolve("avatars"));
    }

    public static Path uploads() {
        return ensure(root().resolve("uploads"));
    }

    public static Path encrypted() {
        return ensure(root().resolve("uploads").resolve("encrypted"));
    }

    /** Папка для отдачи статики: Spring ждёт адрес вида file:/путь/. */
    public static String avatarsLocation() {
        return avatars().toUri().toString();
    }

    private static Path ensure(Path path) {
        File directory = path.toFile();
        if (!directory.exists()) {
            // Неудача не повод падать: обработчик загрузки сам скажет, что не вышло
            directory.mkdirs();
        }
        return path;
    }
}
