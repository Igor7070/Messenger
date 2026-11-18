package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.messenger.offline.OfflineModeActivity;
import com.example.unlimitedpossibilities12.messenger.offline.OfflinePreferences;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.List;

/**
 * Настройки приложения.
 *
 * Единого экрана настроек в приложении не было: уведомления нельзя было
 * приглушить, язык перевода выбирался заново в каждой переписке, а
 * офлайн-режим жил в выпадающем меню списка чатов. Здесь только то, что
 * действительно работает — мёртвых переключателей не ставим.
 */
public class SettingsActivity extends AppCompatActivity {

    private TextView translationValue, offlineValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        findViewById(R.id.settings_back).setOnClickListener(v -> finish());

        translationValue = findViewById(R.id.value_translation_language);
        offlineValue = findViewById(R.id.value_offline_mode);

        setupNotifications();
        setupChats();
        setupStorage();
        showVersion();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Офлайн-режим включается на своём экране — возвращаемся со свежим
        showOfflineState();
    }

    // ---------- уведомления ----------

    private void setupNotifications() {
        SwitchMaterial messages = findViewById(R.id.switch_message_notifications);
        SwitchMaterial sound = findViewById(R.id.switch_notification_sound);

        messages.setChecked(AppSettings.messageNotifications(this));
        sound.setChecked(AppSettings.notificationSound(this));
        sound.setEnabled(messages.isChecked());

        findViewById(R.id.row_message_notifications).setOnClickListener(v -> {
            boolean on = !messages.isChecked();
            messages.setChecked(on);
            AppSettings.setMessageNotifications(this, on);
            // Звук без самих уведомлений смысла не имеет
            sound.setEnabled(on);
        });

        findViewById(R.id.row_notification_sound).setOnClickListener(v -> {
            if (!messages.isChecked()) return;
            boolean on = !sound.isChecked();
            sound.setChecked(on);
            AppSettings.setNotificationSound(this, on);
        });

        SwitchMaterial preview = findViewById(R.id.switch_notification_preview);
        preview.setChecked(AppSettings.notificationPreview(this));
        preview.setEnabled(messages.isChecked());
        findViewById(R.id.row_notification_preview).setOnClickListener(v -> {
            if (!messages.isChecked()) return;
            boolean on = !preview.isChecked();
            preview.setChecked(on);
            AppSettings.setNotificationPreview(this, on);
            // Сервер должен знать, что класть в push: текст или общую подпись.
            com.example.unlimitedpossibilities12.messenger.notife.PushMessagingService
                    .sendTokenToServer(this);
        });

        findViewById(R.id.row_system_notifications).setOnClickListener(v -> openSystemNotifications());
    }

    /** Разрешение и канал живут в настройках Android — отправляем туда. */
    private void openSystemNotifications() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:" + getPackageName()));
        }
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Android settings are not available", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- переписка ----------

    private void setupChats() {
        showTranslationLanguage();
        findViewById(R.id.row_translation_language).setOnClickListener(v -> showLanguagePicker());

        findViewById(R.id.row_offline_mode).setOnClickListener(v ->
                startActivity(new Intent(this, OfflineModeActivity.class)));
        showOfflineState();
    }

    private void showTranslationLanguage() {
        translationValue.setText(Languages.labelOf(AppSettings.defaultTranslationLanguage(this)));
    }

    /**
     * Язык по умолчанию для переписок, где язык ещё не выбирали руками.
     * Список тот же, что в окне перевода внутри чата.
     */
    private void showLanguagePicker() {
        List<Languages.Language> languages = Languages.all();
        CharSequence[] labels = new CharSequence[languages.size()];
        for (int i = 0; i < languages.size(); i++) labels[i] = languages.get(i).label();

        int current = Languages.indexOf(AppSettings.defaultTranslationLanguage(this));

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.AlertDialog_UP12)
                .setTitle(R.string.settings_translation_language)
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    AppSettings.setDefaultTranslationLanguage(this, languages.get(which).code);
                    showTranslationLanguage();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showOfflineState() {
        if (offlineValue == null) return;
        boolean on = new OfflinePreferences(this).isMeshEnabled();
        offlineValue.setText(on ? R.string.settings_offline_on : R.string.settings_offline_off);
    }

    // ---------- память ----------

    private void setupStorage() {
        findViewById(R.id.row_clear_cache).setOnClickListener(v -> clearCache());
    }

    /**
     * Снимки и звуковые дорожки голосовых держатся на телефоне, чтобы не
     * качать их снова. Чистим и то и другое — следующий заход просто
     * загрузит заново.
     */
    private void clearCache() {
        Context context = getApplicationContext();
        Glide.get(context).clearMemory();
        Waveforms.clearCache(context);

        new Thread(() -> {
            Glide.get(context).clearDiskCache();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(this, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    // ---------- о приложении ----------

    private void showVersion() {
        TextView version = findViewById(R.id.value_version);
        String name = "—";
        try {
            name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        version.setText(getString(R.string.settings_version, name));
    }
}
