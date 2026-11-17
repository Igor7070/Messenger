package com.example.unlimitedpossibilities12.messenger;

import android.view.View;
import android.widget.TextView;

import com.example.unlimitedpossibilities12.R;

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

        // Подвал меряется заново при каждой привязке, и ширина его гуляет на
        // точку-другую: хвост становился то длиннее, то короче на один пробел,
        // и значки на глазах переезжали. Для той же самой строки держимся
        // прежней длины, пока разница не станет настоящей.
        String rawText = raw.toString();
        Object[] memo = asMemo(text.getTag(R.id.message_footer));
        if (memo != null && rawText.equals(memo[0])
                && Math.abs(((Integer) memo[1]) - count) <= 1) {
            count = (Integer) memo[1];
        } else {
            text.setTag(R.id.message_footer, new Object[]{rawText, count});
        }

        if (tailLength(text.getText()) == count && text.getText().length() == raw.length() + count) {
            return;
        }

        StringBuilder tail = new StringBuilder(count);
        for (int i = 0; i < count; i++) tail.append(FILLER);
        text.setText(raw + tail.toString());
    }

    /**
     * Хвост носит только последняя видимая строка. У остальных его надо убрать:
     * строка с забытым хвостом держит пузырь шире, и когда её потом перепишут,
     * пузырь на глазах сужается вместе со всеми значками подвала.
     */
    public static void keepTailOnly(TextView keep, TextView... all) {
        for (TextView view : all) {
            if (view == null || view == keep) continue;
            CharSequence current = view.getText();
            if (current == null || current.length() == 0) continue;
            CharSequence bare = stripTail(current);
            if (bare.length() != current.length()) view.setText(bare);
        }
    }

    /** Что запомнили для этой строки: сам текст и длину хвоста. */
    private static Object[] asMemo(Object tag) {
        if (!(tag instanceof Object[])) return null;
        Object[] memo = (Object[]) tag;
        if (memo.length != 2 || !(memo[0] instanceof String) || !(memo[1] instanceof Integer)) return null;
        return memo;
    }

    /** Сколько знаков хвоста уже дописано к строке. */
    private static int tailLength(CharSequence value) {
        if (value == null) return 0;
        int end = value.length();
        int count = 0;
        while (end > 0 && value.charAt(end - 1) == FILLER_CHAR) {
            end--;
            count++;
        }
        return count;
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

    /**
     * Подвал пустой, когда время неизвестно и ни один значок не горит.
     * Тогда полоса под содержимым ни к чему: у снимка под подписью
     * оставалась ничем не занятая пустота в треть сантиметра.
     */
    public static boolean isEmpty(View footer) {
        if (footer == null) return true;
        if (!(footer instanceof android.view.ViewGroup)) return footer.getVisibility() != View.VISIBLE;
        android.view.ViewGroup group = (android.view.ViewGroup) footer;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof android.widget.TextView
                    && ((android.widget.TextView) child).getText().length() == 0) continue;
            return false;
        }
        return true;
    }
}
