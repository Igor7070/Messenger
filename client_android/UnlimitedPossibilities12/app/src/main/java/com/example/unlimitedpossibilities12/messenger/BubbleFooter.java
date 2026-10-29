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
        float space = text.getPaint().measureText(" ");
        if (space <= 0) {
            text.setText(raw);
            return;
        }
        int count = (int) Math.ceil(width / space);
        StringBuilder tail = new StringBuilder(count);
        for (int i = 0; i < count; i++) tail.append(' ');
        text.setText(raw + tail.toString());
    }

    /** Убирает прежний хвост, чтобы он не накапливался при переиспользовании вьюхи. */
    public static CharSequence stripTail(CharSequence value) {
        if (value == null) return "";
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') end--;
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
