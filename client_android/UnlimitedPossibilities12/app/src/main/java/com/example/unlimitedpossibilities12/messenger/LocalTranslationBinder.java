package com.example.unlimitedpossibilities12.messenger;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;

import com.example.unlimitedpossibilities12.R;

import java.util.HashMap;
import java.util.Map;

/**
 * Показ строки «перевод на устройстве» под сообщением — общий для секретного
 * и офлайн-чата. Правило: готовый перевод из обёртки {@link SecretText}, если
 * он на нужном языке; иначе переводим сами (если пакеты есть); иначе ничего.
 * Результаты кэшируются по ключу сообщения, чтобы не переводить при каждой
 * перерисовке списка.
 */
public final class LocalTranslationBinder {
    private final Activity activity;
    private final long chatKey;
    private final Map<String, String> cache = new HashMap<>();

    public LocalTranslationBinder(Activity activity, long chatKey) {
        this.activity = activity;
        this.chatKey = chatKey;
    }

    public void clearCache() { cache.clear(); }

    public boolean isOn() {
        return LocalTranslator.isEnabled(activity, chatKey) && LocalTranslator.targetLanguage(activity, chatKey) != null;
    }

    public void bind(TextView view, String msgKey, SecretText st) {
        if (view == null) return;
        view.setVisibility(View.GONE);
        if (st == null || st.text.isEmpty() || !isOn()) return;
        String tgt = LocalTranslator.targetLanguage(activity, chatKey);

        if (st.translated != null && tgt.equals(st.lang)) { show(view, st.translated); return; }

        String cacheKey = msgKey + "|" + tgt;
        String cached = cache.get(cacheKey);
        if (cached != null) { if (!cached.isEmpty()) show(view, cached); return; }

        view.setTag(R.id.translated_content_text_view, cacheKey);
        LocalTranslator.translateToIfNeeded(st.text, tgt).addOnCompleteListener(t -> {
            String result = (t.isSuccessful() && t.getResult() != null) ? t.getResult() : "";
            cache.put(cacheKey, result); // "" = уже на целевом языке / нечего переводить
            activity.runOnUiThread(() -> {
                if (!cacheKey.equals(view.getTag(R.id.translated_content_text_view))) return; // holder переиспользован
                if (!result.isEmpty()) show(view, result); else view.setVisibility(View.GONE);
            });
        });
    }

    private static void show(TextView view, String translated) {
        view.setText(translated + "  ·  translated on device");
        view.setVisibility(View.VISIBLE);
    }
}
