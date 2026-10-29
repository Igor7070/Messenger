package com.example.unlimitedpossibilities12.messenger;

import android.widget.ListPopupWindow;
import android.widget.ListView;
import android.widget.Spinner;

import androidx.core.content.ContextCompat;

import com.example.unlimitedpossibilities12.R;

/**
 * Скруглённые углы у раскрытого списка Spinner. Список рисуется в отдельном
 * окне со своим непрозрачным фоном, который перекрывает скругление подложки;
 * здесь фон окна и самого ListView заменяется на нашу скруглённую подложку,
 * а список обрезается по её контуру.
 */
public final class SpinnerStyle {
    private SpinnerStyle() {}

    public static void roundDropdown(Spinner spinner) {
        roundDropdown(spinner, 0);
    }

    /** @param maxHeightDp ограничение высоты списка (0 = не ограничивать). */
    public static void roundDropdown(Spinner spinner, int maxHeightDp) {
        if (spinner == null) return;
        try {
            java.lang.reflect.Field f = Spinner.class.getDeclaredField("mPopup");
            f.setAccessible(true);
            Object popup = f.get(spinner);
            if (!(popup instanceof ListPopupWindow)) return;
            ListPopupWindow lpw = (ListPopupWindow) popup;
            android.graphics.drawable.Drawable bg =
                    ContextCompat.getDrawable(spinner.getContext(), R.drawable.spinner_dropdown_background_translate);
            lpw.setBackgroundDrawable(bg);
            if (maxHeightDp > 0) {
                int px = Math.round(maxHeightDp * spinner.getResources().getDisplayMetrics().density);
                lpw.setHeight(px);
            }

            // ListView создаётся лениво при первом открытии — поэтому стилизуем его
            // в момент показа, а не при настройке спиннера.
            spinner.setOnTouchListener((v, ev) -> {
                if (ev.getAction() == android.view.MotionEvent.ACTION_UP) {
                    v.post(() -> styleList(lpw, bg));
                }
                return false;
            });
            styleList(lpw, bg);
        } catch (Exception ignored) {
            // на некоторых прошивках поле недоступно — остаётся стандартный вид
        }
    }

    private static void styleList(ListPopupWindow lpw, android.graphics.drawable.Drawable bg) {
        ListView lv = lpw.getListView();
        if (lv == null) return;
        lv.setBackground(bg.getConstantState() != null ? bg.getConstantState().newDrawable().mutate() : bg);
        lv.setClipToOutline(true);
        lv.setDivider(null);
        lv.setSelector(android.R.color.transparent);
        lv.setVerticalScrollBarEnabled(false);
    }
}
