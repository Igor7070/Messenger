package com.example.unlimitedpossibilities12.messenger;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class LetterAvatarDrawable extends Drawable {
    private final String letter;
    private final Paint paint;

    public LetterAvatarDrawable(String letter) {
        this.letter = letter;
        this.paint = new Paint();
        paint.setColor(Color.parseColor("#B0BEC5"));
        paint.setTextSize(48f);
        paint.setAntiAlias(true);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        int width = getBounds().width();
        int height = getBounds().height();
        canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint);
        paint.setColor(Color.WHITE);
        canvas.drawText(letter, width / 2f, height / 2f + 16f, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
