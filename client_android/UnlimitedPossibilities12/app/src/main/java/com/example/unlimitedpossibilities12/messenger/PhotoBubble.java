package com.example.unlimitedpossibilities12.messenger;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import com.example.unlimitedpossibilities12.R;

/**
 * Снимок в пузыре переписки.
 *
 * Разметка тут бессильна: рисунок приходит разными путями — из сети, из
 * расшифрованного файла, из временной копии ещё не отправленного — и в
 * половине случаев ложился в середину вида в натуральную величину. По краям
 * оставалась полоса пузыря, будто фотографию вставили в рамку. Поэтому размер
 * задаём сами, как только рисунок известен: ширина у всех снимков одна, высота
 * — по пропорциям, с потолком, за которым вертикальная фотография обрезается,
 * а не растягивает переписку на весь экран.
 */
final class PhotoBubble {

    private static final int WIDTH_DP = 252;
    private static final int MAX_HEIGHT_DP = 440;
    private static final int MIN_WIDTH_DP = 150;
    private static final int CORNER_DP = 14;

    private PhotoBubble() {
    }

    /** Один раз на держателя строки: дальше вид сам держит себя в размере. */
    static void keepInShape(final ImageView view) {
        if (view == null || view.getTag(R.id.image_preview) != null) return;
        view.setTag(R.id.image_preview, Boolean.TRUE);
        // Подгонка, а не обрезка: при обрезке Glide резал сам рисунок под
        // размер вида — у снимка пропадали верх и низ. Размер вида мы и так
        // считаем по пропорциям снимка, так что полей от подгонки не будет.
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setAdjustViewBounds(false);
        // Скругление держим на самом снимке: карточка обрезала не все углы —
        // правый верхний оставался квадратным и бросался в глаза.
        final float radius = CORNER_DP * view.getResources().getDisplayMetrics().density;
        view.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        view.setClipToOutline(true);
        view.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                return applySize(view);
            }
        });
    }

    /** @return false — размер изменился, кадр нужно пересобрать. */
    private static boolean applySize(ImageView view) {
        if (view.getVisibility() != View.VISIBLE) return true;
        Drawable drawable = view.getDrawable();
        if (drawable == null || drawable.getIntrinsicWidth() <= 0 || drawable.getIntrinsicHeight() <= 0) {
            return true;
        }
        float density = view.getResources().getDisplayMetrics().density;
        int width = Math.round(WIDTH_DP * density);
        int maxHeight = Math.round(MAX_HEIGHT_DP * density);
        int height = Math.round(width * drawable.getIntrinsicHeight() / (float) drawable.getIntrinsicWidth());
        // Высокую фотографию не обрезаем, а сужаем: обрезка съедала у
        // вертикальных снимков верх и низ, и было не понять, что на них.
        if (height > maxHeight) {
            height = maxHeight;
            width = Math.max(Math.round(MIN_WIDTH_DP * density),
                    Math.round(height * drawable.getIntrinsicWidth() / (float) drawable.getIntrinsicHeight()));
        }

        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null || (params.width == width && params.height == height)) return true;
        params.width = width;
        params.height = height;
        view.setLayoutParams(params);
        return false;
    }

    /**
     * Снимок во всю ширину пузыря. Поля пузыря (12 точек по кругу) обрезали
     * фотографию с боков и сверху — выходила рамка, которой в переписке не
     * место. У сообщения со снимком поля снимаем, а подписи отдаём их сами,
     * чтобы текст не прилипал к краю.
     */
    static void flushToEdges(View column, boolean hasPhoto, boolean keepTop, int padding,
                             android.widget.TextView... texts) {
        if (column == null) return;
        int side = hasPhoto ? 0 : padding;
        // Сверху поле оставляем, когда над снимком стоит имя отправителя:
        // в группе оно иначе прилипло бы к самому краю пузыря.
        int top = hasPhoto && !keepTop ? 0 : padding;
        if (column.getPaddingLeft() != side || column.getPaddingTop() != top) {
            column.setPadding(side, top, side, column.getPaddingBottom());
        }
        int textSide = hasPhoto ? padding : 0;
        for (android.widget.TextView text : texts) {
            if (text == null) continue;
            if (text.getPaddingLeft() != textSide) {
                text.setPadding(textSide, text.getPaddingTop(), textSide, text.getPaddingBottom());
            }
        }
    }
}
