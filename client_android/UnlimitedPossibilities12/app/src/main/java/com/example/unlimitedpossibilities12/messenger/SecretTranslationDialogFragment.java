package com.example.unlimitedpossibilities12.messenger;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.example.unlimitedpossibilities12.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройки локального перевода для секретного чата: включить/выключить,
 * мой язык, язык собеседника, загрузка языковых пакетов по явному согласию.
 */
public class SecretTranslationDialogFragment extends DialogFragment {

    public interface Listener {
        void onSaved(boolean enabled, String source, String target);
    }

    private final long chatId;
    private final Listener listener;
    private final String note; // подпись под заголовком; null = по умолчанию (секретный чат)

    private Switch enabledSwitch;
    private Spinner sourceSpinner, targetSpinner;
    private CheckBox wifiOnly;
    private TextView statusText;
    private ProgressBar progress;
    private Button downloadButton, saveButton;

    private final List<String> codes = new ArrayList<>(LocalTranslator.SUPPORTED.keySet());

    public SecretTranslationDialogFragment(long chatId, Listener listener) {
        this(chatId, listener, null);
    }

    public SecretTranslationDialogFragment(long chatId, Listener listener, String note) {
        this.chatId = chatId;
        this.listener = listener;
        this.note = note;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        View v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_secret_translation, null);
        enabledSwitch = v.findViewById(R.id.translation_enabled);
        if (note != null) ((TextView) v.findViewById(R.id.translation_note)).setText(note);
        sourceSpinner = v.findViewById(R.id.source_language);
        targetSpinner = v.findViewById(R.id.target_language);
        wifiOnly = v.findViewById(R.id.wifi_only);
        statusText = v.findViewById(R.id.pack_status);
        progress = v.findViewById(R.id.pack_progress);
        downloadButton = v.findViewById(R.id.download_button);
        saveButton = v.findViewById(R.id.save_button);

        List<String> labels = new ArrayList<>();
        for (String c : codes) labels.add(LocalTranslator.label(c));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_item_translate, labels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_secret); // прозрачные строки — видна скруглённая подложка
        sourceSpinner.setAdapter(adapter);
        targetSpinner.setAdapter(adapter);

        // Текущие настройки
        enabledSwitch.setChecked(LocalTranslator.isEnabled(requireContext(), chatId));
        String src = LocalTranslator.sourceLanguage(requireContext(), chatId);
        String tgt = LocalTranslator.targetLanguage(requireContext(), chatId);
        // «Мой язык» больше не выбирается: исходный язык определяется автоматически.
        sourceSpinner.setVisibility(View.GONE);
        v.findViewById(R.id.source_label).setVisibility(View.GONE);
        targetSpinner.setSelection(tgt != null ? Math.max(0, codes.indexOf(tgt)) : Math.min(1, codes.size() - 1));
        SpinnerStyle.roundDropdown(targetSpinner); // скруглённые углы у раскрытого списка

        refreshPackStatus();
        targetSpinner.setOnItemSelectedListener(new SimpleSelect(this::refreshPackStatus));

        downloadButton.setOnClickListener(x -> download());
        saveButton.setOnClickListener(x -> save());
        v.findViewById(R.id.cancel_button).setOnClickListener(x -> dismiss());

        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setView(v).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        return dialog;
    }

    /** Все пакеты, нужные для перевода на target с любого поддерживаемого языка. */
    private java.util.Set<String> packsForTarget() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (String c : codes) set.addAll(LocalTranslator.packsNeeded(c, target()));
        return set;
    }
    private String source() { return target(); } // совместимость с save()
    private String target() { return codes.get(targetSpinner.getSelectedItemPosition()); }

    private void refreshPackStatus() {
        java.util.Set<String> need = packsForTarget();
        LocalTranslator.downloadedPacks().addOnCompleteListener(t -> {
            if (!isAdded()) return;
            java.util.Set<String> have = new java.util.HashSet<>();
            if (t.isSuccessful() && t.getResult() != null) {
                for (com.google.mlkit.nl.translate.TranslateRemoteModel m : t.getResult()) have.add(m.getLanguage());
            }
            java.util.Set<String> missing = new java.util.LinkedHashSet<>(need);
            missing.removeAll(have);
            if (missing.isEmpty()) {
                statusText.setText("Language packs are on this device ✓ — translation works offline");
                downloadButton.setVisibility(View.GONE);
                saveButton.setEnabled(true);
            } else {
                StringBuilder names = new StringBuilder();
                for (String m : missing) { if (names.length() > 0) names.append(", "); names.append(LocalTranslator.label(m)); }
                statusText.setText("Language packs needed: " + names + " (~" + (missing.size() * LocalTranslator.PACK_SIZE_MB)
                        + " MB, downloaded once). Your messages are never sent anywhere for translation.");
                downloadButton.setVisibility(View.VISIBLE);
                downloadButton.setEnabled(true);
                saveButton.setEnabled(false);
            }
        });
    }

    private void download() {
        downloadButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        statusText.setText("Downloading language packs…");
        LocalTranslator.downloadPacks(packsForTarget(), wifiOnly.isChecked()).addOnCompleteListener(t -> {
            if (!isAdded()) return;
            progress.setVisibility(View.GONE);
            if (t.isSuccessful()) {
                refreshPackStatus();
            } else {
                String why = t.getException() != null ? t.getException().getMessage() : "";
                statusText.setText("Download failed: " + why
                        + "\nLanguage packs are downloaded once and need internet — "
                        + (wifiOnly.isChecked() ? "check Wi-Fi or untick \"Wi-Fi only\"." : "download them when you are online."));
                downloadButton.setEnabled(true);
            }
        });
    }

    private void save() {
        LocalTranslator.save(requireContext(), chatId, enabledSwitch.isChecked(), source(), target());
        if (listener != null) listener.onSaved(enabledSwitch.isChecked(), source(), target());
        Toast.makeText(requireContext(), enabledSwitch.isChecked()
                ? "On-device translation enabled" : "Translation disabled", Toast.LENGTH_SHORT).show();
        dismiss();
    }

    /** Упрощённый слушатель Spinner. */
    private static class SimpleSelect implements android.widget.AdapterView.OnItemSelectedListener {
        private final Runnable r;
        SimpleSelect(Runnable r) { this.r = r; }
        @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { r.run(); }
        @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
    }
}
