package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import com.example.unlimitedpossibilities12.R;
import com.google.android.material.button.MaterialButton;

public class TranscriptionSettingsFragment extends DialogFragment {
    public enum TranscriptionMode {
        ALL, NEW_ONLY, SELECTIVELY
    }

    private OnTranscriptionSettingsListener listener;

    public interface OnTranscriptionSettingsListener {
        void onTranscriptionSettingsChanged(boolean enabled, TranscriptionMode mode);
    }

    public TranscriptionSettingsFragment(OnTranscriptionSettingsListener listener) {
        this.listener = listener;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_transcription_settings, container, false);

        view.setAnimation(AnimationUtils.loadAnimation(requireContext(), R.anim.dialog_fade_in_translate));

        CheckBox enableTranscription = view.findViewById(R.id.enable_transcription);
        RadioGroup transcriptionModeGroup = view.findViewById(R.id.transcription_mode_group);
        RadioButton transcribeAllRadio = view.findViewById(R.id.transcribe_all);
        RadioButton transcribeNewOnlyRadio = view.findViewById(R.id.transcribe_new_only);
        RadioButton transcribeSelectivelyRadio = view.findViewById(R.id.transcribe_selectively);
        MaterialButton saveButton = view.findViewById(R.id.save_button);
        MaterialButton cancelButton = view.findViewById(R.id.cancel_button);

        SharedPreferences prefs = requireContext().getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
        long chatId = getArguments() != null ? getArguments().getLong("chatId", -1) : -1;
        boolean isTranscriptionEnabled = prefs.getBoolean("transcription_enabled_" + chatId, false);
        TranscriptionMode transcriptionMode = TranscriptionMode.valueOf(prefs.getString("transcription_mode_" + chatId, TranscriptionMode.ALL.name()));

        enableTranscription.setChecked(isTranscriptionEnabled);
        if (transcriptionMode == TranscriptionMode.ALL) {
            transcribeAllRadio.setChecked(true);
        } else if (transcriptionMode == TranscriptionMode.NEW_ONLY) {
            transcribeNewOnlyRadio.setChecked(true);
        } else {
            transcribeSelectivelyRadio.setChecked(true);
        }

        saveButton.setOnClickListener(v -> {
            boolean enabled = enableTranscription.isChecked();
            TranscriptionMode mode = transcribeAllRadio.isChecked() ? TranscriptionMode.ALL :
                    transcribeNewOnlyRadio.isChecked() ? TranscriptionMode.NEW_ONLY : TranscriptionMode.SELECTIVELY;
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean("transcription_enabled_" + chatId, enabled);
            editor.putString("transcription_mode_" + chatId, mode.name());
            editor.apply();
            listener.onTranscriptionSettingsChanged(enabled, mode);
            dismiss();
        });

        cancelButton.setOnClickListener(v -> dismiss());

        return view;
    }
}
