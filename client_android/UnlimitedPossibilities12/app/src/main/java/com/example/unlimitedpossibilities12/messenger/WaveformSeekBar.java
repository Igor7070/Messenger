package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatSeekBar;

/**
 * Полоса голосового сообщения в виде звуковой волны.
 *
 * Снаружи это обычный SeekBar: та же перемотка пальцем, те же setMax,
 * setProgress и слушатель. Меняется только рисование — вместо линии с
 * бегунком столбики по громкости записи, прослушанная часть подсвечена.
 * Ровно так выглядят голосовые в телефонных мессенджерах.
 */
public class WaveformSeekBar extends AppCompatSeekBar {

    /** Громкость по кусочкам записи, 0..100. Пока не посчитана — null. */
    private byte[] levels;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();

    private int playedColor = 0xFF67E8F9;
    private int restColor = 0xFF64748B;

    private final float barWidth;
    private final float gap;
    private final float minHeight;

    public WaveformSeekBar(Context context) {
        this(context, null);
    }

    public WaveformSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        barWidth = 3 * density;
        gap = 2 * density;
        minHeight = 2 * density;
        // Родное оформление убираем: дорожку и бегунок рисуем сами
        setThumb(null);
        setThumbOffset(0);
        setBackground(null);
        setSplitTrack(false);
        setPadding(0, getPaddingTop(), 0, getPaddingBottom());
    }

    /** Цвета: прослушанная часть и остаток. У своих и чужих пузырей разные. */
    public void setWaveColors(int played, int rest) {
        if (playedColor == played && restColor == rest) return;
        playedColor = played;
        restColor = rest;
        invalidate();
    }

    /** Громкость записи по кусочкам, 0..100; null — пока не посчитана. */
    public void setLevels(byte[] levels) {
        this.levels = levels;
        invalidate();
    }

    public byte[] getLevels() {
        return levels;
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        boolean handled = super.onTouchEvent(event);
        // Как только волна взяла перемотку себе, лента не должна уводить
        // палец в прокрутку — иначе точку не поймать
        if (isPressed() && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return handled;
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        // super не зовём: родной SeekBar нарисовал бы поверх волны свою дорожку
        int width = getWidth() - getPaddingLeft() - getPaddingRight();
        int height = getHeight() - getPaddingTop() - getPaddingBottom();
        if (width <= 0 || height <= 0) return;

        float step = barWidth + gap;
        int count = (int) ((width + gap) / step);
        if (count < 1) return;

        int max = getMax();
        float playedTo = max > 0 ? width * (getProgress() / (float) max) : 0f;
        float radius = barWidth / 2f;
        float left = getPaddingLeft();
        float centerY = getPaddingTop() + height / 2f;

        for (int i = 0; i < count; i++) {
            float x = left + i * step;
            float level = levelAt(i, count);
            float barHeight = Math.max(minHeight, height * level / 100f);
            bar.set(x, centerY - barHeight / 2f, x + barWidth, centerY + barHeight / 2f);
            // Столбик считается прослушанным по своей середине — так полоса
            // растёт ровно и не мигает на границе
            paint.setColor(x + barWidth / 2f <= left + playedTo ? playedColor : restColor);
            canvas.drawRoundRect(bar, radius, radius, paint);
        }
    }

    /**
     * Громкость для столбика: усредняем попавшие в него кусочки. Если брать
     * самый громкий, вся волна слипается в сплошную стену.
     */
    private float levelAt(int index, int count) {
        if (levels == null || levels.length == 0) return 22f; // ровная полоска, пока волны нет
        int from = (int) ((long) index * levels.length / count);
        int to = (int) ((long) (index + 1) * levels.length / count);
        if (to <= from) to = from + 1;
        if (to > levels.length) to = levels.length;
        int sum = 0;
        int taken = 0;
        for (int i = from; i < to; i++) {
            sum += levels[i] & 0xFF;
            taken++;
        }
        float average = taken == 0 ? 22f : sum / (float) taken;
        return Math.min(100f, Math.max(8f, average));
    }
}
