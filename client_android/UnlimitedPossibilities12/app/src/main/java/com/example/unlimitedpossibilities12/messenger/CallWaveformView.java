package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class CallWaveformView extends View {
    private Paint paint;
    private List<Float> amplitudes;
    private Random random;
    private static final int MAX_AMPLITUDES = 50;

    public CallWaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint = new Paint();
        paint.setColor(Color.parseColor("#4CAF50"));
        paint.setStrokeWidth(4f);
        paint.setAntiAlias(true);
        amplitudes = new ArrayList<>();
        random = new Random();
    }

    public void updateAmplitude() {
        float amplitude = random.nextFloat() * 100; // Случайная амплитуда
        amplitudes.add(amplitude);
        if (amplitudes.size() > MAX_AMPLITUDES) {
            amplitudes.remove(0);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Отрисовка осей
        Paint axisPaint = new Paint();
        axisPaint.setColor(Color.WHITE);
        axisPaint.setStrokeWidth(2f);
        canvas.drawLine(20, 0, 20, getHeight(), axisPaint); // Y-ось
        canvas.drawLine(20, getHeight() / 2, getWidth(), getHeight() / 2, axisPaint); // X-ось

        // Отрисовка меток X-оси (через каждые 20 секунд)
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(32f); // Увеличен размер текста до 32sp

        // Предполагаем, что график отображает 60 секунд (для примера)
        float width = getWidth() - 20;
        int totalTime = 60; // Общее время графика в секундах
        int interval = 20; // Интервал меток в секундах
        int numMarks = totalTime / interval + 1; // Количество меток

        for (int i = 0; i < numMarks; i++) {
            float x = 20 + (width * i * interval) / totalTime;
            String label = (i * interval) + "s";
            canvas.drawText(label, x, getHeight() / 2 + 40, textPaint); // Смещение вниз для видимости
        }

        // Отрисовка волны
        float height = getHeight();
        float centerY = height / 2;

        if (amplitudes.isEmpty()) {
            canvas.drawLine(20, centerY, width + 20, centerY, paint);
            return;
        }

        float step = width / MAX_AMPLITUDES;
        for (int i = 0; i < amplitudes.size() - 1; i++) {
            float x1 = 20 + i * step;
            float x2 = 20 + (i + 1) * step;
            float y1 = centerY - (amplitudes.get(i) / 100 * centerY * 0.8f);
            float y2 = centerY - (amplitudes.get(i + 1) / 100 * centerY * 0.8f);
            canvas.drawLine(x1, y1, x2, y2, paint);
        }
    }
}
