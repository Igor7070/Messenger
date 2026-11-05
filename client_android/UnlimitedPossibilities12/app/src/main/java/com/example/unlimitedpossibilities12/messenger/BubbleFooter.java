package com.example.unlimitedpossibilities12.messenger;

import android.view.View;
import android.widget.TextView;

/**
 * Время в одной строке с текстом сообщения.
 *
 * Android сам так не умеет: подвал пузыря (значки, галочки, время) — отдельная
 * вьюха, и в вертикальной колонке он всегда уходит на новую строку. Поэтому
 * подвал лежит накладкой в правом нижнем углу, а к последней строке текста
 * дописывается «хвост» из пробелов шириной с подвал. Пока хвост влезает, время
 * стоит рядом с текстом; как только перестаёт — он переносится, и время
 * оказывается на своей строке. Ровно так ведут себя телефонные мессенджеры.
 */
public final class BubbleFooter {

    /** Неразрывный пробел: в отличие от обычного, он не отбрасывается при измерении строки. */
    private static final char FILLER_CHAR = ' ';
    private static final String FILLER = String.valueOf(FILLER_CHAR);

    private BubbleFooter() {
    }

    /**
     * Дописывает хвост к тексту под ширину подвала.
     *
     * @param text   последняя видимая строка пузыря (сам текст или перевод)
     * @param width  ширина подвала в пикселях
     */
    public static void applyTail(TextView text, float width) {
        if (text == null || text.getVisibility() != View.VISIBLE) return;
        CharSequence raw = stripTail(text.getText());
        if (width <= 0) {
            text.setText(raw);
            return;
        }
        // Обычные пробелы в конце строки Android при измерении отбрасывает, и
        // пузырь не становится шире — подвал ложится прямо на текст. Неразрывный
        // пробел считается обычным символом, поэтому ширина растёт как надо.
        float space = text.getPaint().measureText(FILLER);
        if (space <= 0) {
            text.setText(raw);
            return;
        }
        int count = (int) Math.ceil(width / space);
        StringBuilder tail = new StringBuilder(count);
        for (int i = 0; i < count; i++) tail.append(FILLER);
        text.setText(raw + tail.toString());
    }

    /** Убирает прежний хвост, чтобы он не накапливался при переиспользовании вьюхи. */
    public static CharSequence stripTail(CharSequence value) {
        if (value == null) return "";
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == FILLER_CHAR)) end--;
        return value.subSequence(0, end);
    }

    /**
     * Запас снизу для пузырей, где хвост не поможет, — с картинкой, файлом или
     * аудио: подвал стоит под содержимым, как раньше.
     */
    public static void reserveBelow(View column, boolean reserve, int basePadding, int footerHeight) {
        if (column == null) return;
        column.setPadding(column.getPaddingLeft(), column.getPaddingTop(), column.getPaddingRight(),
                reserve ? basePadding + footerHeight : basePadding);
    }
}
