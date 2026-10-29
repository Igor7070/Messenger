package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.util.Log;

import com.example.unlimitedpossibilities12.api.Message;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Хвост переписки на устройстве.
 *
 * Без него вход в чат начинался с пустого экрана: сообщения каждый раз шли с
 * сервера, и первую секунду смотреть было не на что. Сохраняем последние
 * сообщения и показываем их сразу, а ответ сервера приходит следом и заменяет
 * список. Так открываются все телефонные мессенджеры.
 *
 * Секретные чаты сюда не попадают: их содержимое расшифровано только в памяти,
 * и класть его на диск открытым текстом нельзя.
 */
public final class MessageCache {

    private static final String TAG = "MessageCache";
    private static final String DIR = "chat_cache";
    /** Хватает на пару экранов — больше на открытии всё равно не видно. */
    private static final int MAX_MESSAGES = 60;

    private static final Type LIST_TYPE = new TypeToken<List<Message>>() {
    }.getType();

    // Message ссылается на Chat, а Chat — обратно на список сообщений.
    // Для записи на диск эту ссылку опускаем, иначе Gson уйдёт по кругу.
    private static final Gson GSON = new GsonBuilder()
            .addSerializationExclusionStrategy(new ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(FieldAttributes field) {
                    return Message.class.equals(field.getDeclaringClass())
                            && "chat".equals(field.getName());
                }

                @Override
                public boolean shouldSkipClass(Class<?> type) {
                    return false;
                }
            })
            .create();

    private MessageCache() {
    }

    /** Сохранённые сообщения чата; пустой список, если их ещё нет. */
    public static List<Message> load(Context context, long chatId) {
        File file = fileFor(context, chatId);
        if (!file.exists()) return new ArrayList<>();

        try (InputStreamReader reader = new InputStreamReader(
                new java.io.FileInputStream(file), Charset.forName("UTF-8"))) {
            List<Message> saved = GSON.fromJson(reader, LIST_TYPE);
            return saved == null ? new ArrayList<Message>() : saved;
        } catch (Exception e) {
            Log.w(TAG, "Не удалось прочитать кэш чата " + chatId + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Запомнить последние сообщения чата. Пишем в фоне — на экране это не ждут. */
    public static void save(Context context, long chatId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;

        final Context appContext = context.getApplicationContext();
        final List<Message> tail = new ArrayList<>(messages.size() > MAX_MESSAGES
                ? messages.subList(messages.size() - MAX_MESSAGES, messages.size())
                : messages);

        new Thread(() -> {
            File file = fileFor(appContext, chatId);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.w(TAG, "Не удалось создать папку кэша");
                return;
            }

            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), Charset.forName("UTF-8"))) {
                GSON.toJson(tail, LIST_TYPE, writer);
            } catch (Exception e) {
                Log.w(TAG, "Не удалось сохранить кэш чата " + chatId + ": " + e.getMessage());
            }
        }).start();
    }

    /** Забыть переписку чата — например, когда чат удалён. */
    public static void clear(Context context, long chatId) {
        File file = fileFor(context, chatId);
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Не удалось удалить кэш чата " + chatId);
        }
    }

    private static File fileFor(Context context, long chatId) {
        return new File(new File(context.getApplicationContext().getFilesDir(), DIR),
                "chat_" + chatId + ".json");
    }
}
