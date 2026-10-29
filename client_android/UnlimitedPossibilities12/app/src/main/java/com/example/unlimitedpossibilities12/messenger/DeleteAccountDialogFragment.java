package com.example.unlimitedpossibilities12.messenger;

import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

public class DeleteAccountDialogFragment extends DialogFragment {
    public interface OnDeleteAccountListener {
        void onConfirmDelete();
    }

    private OnDeleteAccountListener listener;

    public DeleteAccountDialogFragment(OnDeleteAccountListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setMessage("Are you sure you want to delete your account?")
                .setPositiveButton("OK", (dialog, which) -> {
                    if (listener != null) {
                        listener.onConfirmDelete();
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        return builder.create();
    }
}
