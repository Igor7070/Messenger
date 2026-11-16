package com.example.unlimitedpossibilities12.messenger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.messenger.notife.OngoingCall;

import java.util.Locale;

/**
 * Полоска идущего разговора поверх остальных экранов.
 *
 * Выйдя из экрана звонка, пользователь терял разговор: «назад» закрывал экран,
 * а с ним и звонок. Теперь экран звонка остаётся позади, а сверху едет эта
 * полоска — имя собеседника и время разговора; нажатие возвращает в звонок.
 *
 * Полоска не живёт в разметке экранов: её добавляет {@code attach} в корень
 * любого экрана, кроме самих экранов звонка, и убирает, как только разговор
 * закончился. Так её не нужно вставлять в каждую разметку приложения.
 */
public final class OngoingCallBar {

    private static final String TAG_BAR = "ongoing-call-bar";

    private OngoingCallBar() {}

    /** Повесить полоску на экран, если идёт разговор; иначе снять. */
    public static void attach(Activity activity) {
        if (activity == null) return;
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;

        View existing = root.findViewWithTag(TAG_BAR);
        if (!OngoingCall.isActive()) {
            if (existing != null) removeBar(root, existing);
            return;
        }
        if (existing != null) return; // уже висит

        root.addView(createBar(activity));
        shiftContent(activity, root, dp(activity, 60));
    }

    /**
     * Опустить содержимое экрана на высоту полоски: иначе она ложится поверх
     * заголовка, и шапка экрана оказывается закрыта.
     */
    private static void shiftContent(Activity activity, ViewGroup root, int extra) {
        View content = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (!TAG_BAR.equals(child.getTag())) {
                content = child;
                break;
            }
        }
        if (content == null) return;
        content.setTag(R.id.ongoing_call_shift, content.getPaddingTop());
        content.setPadding(content.getPaddingLeft(), content.getPaddingTop() + extra,
                content.getPaddingRight(), content.getPaddingBottom());
    }

    /** Снять полоску и вернуть содержимому прежний отступ. */
    private static void removeBar(ViewGroup root, View bar) {
        root.removeView(bar);
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            Object saved = child.getTag(R.id.ongoing_call_shift);
            if (saved instanceof Integer) {
                child.setPadding(child.getPaddingLeft(), (Integer) saved,
                        child.getPaddingRight(), child.getPaddingBottom());
                child.setTag(R.id.ongoing_call_shift, null);
            }
        }
    }

    private static View createBar(Activity activity) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setTag(TAG_BAR);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundResource(R.drawable.bg_ongoing_call_bar);
        int pad = dp(activity, 16);
        bar.setPadding(pad, 0, pad, 0);
        bar.setElevation(dp(activity, 6));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 44));
        params.gravity = Gravity.TOP;
        int side = dp(activity, 12);
        params.setMargins(side, dp(activity, 8), side, 0);
        bar.setLayoutParams(params);

        TextView name = new TextView(activity);
        name.setText(OngoingCall.partnerName() != null ? OngoingCall.partnerName() : "");
        name.setTextColor(Color.WHITE);
        name.setTextSize(15);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nameParams);
        bar.addView(name);

        TextView timer = new TextView(activity);
        timer.setTextColor(Color.WHITE);
        timer.setTextSize(15);
        bar.addView(timer);

        tick(activity, bar, timer);

        bar.setOnClickListener(v -> returnToCall(activity));
        return bar;
    }

    /** Время разговора на полоске, а заодно — проверка, не окончен ли он. */
    private static void tick(Activity activity, View bar, TextView timer) {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (bar.getParent() == null) return;
                if (!OngoingCall.isActive()) {
                    removeBar((ViewGroup) bar.getParent(), bar);
                    return;
                }
                long started = OngoingCall.startedAt();
                if (started == 0) {
                    timer.setText(R.string.call_bar_connecting);
                } else {
                    long seconds = (System.currentTimeMillis() - started) / 1000;
                    timer.setText(String.format(Locale.getDefault(), "%02d:%02d",
                            seconds / 60, seconds % 60));
                }
                handler.postDelayed(this, 1000);
            }
        });
    }

    /**
     * Назад в разговор. Экран звонка всё это время жив и лежит в стопке
     * экранов — поднимаем его наверх, а не создаём заново: второй экран на тот
     * же звонок оборвал бы соединение.
     */
    private static void returnToCall(Activity activity) {
        Class<? extends Activity> screen = OngoingCall.screen();
        if (screen == null) return;
        Intent intent = new Intent(activity, screen);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        activity.startActivity(intent);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
