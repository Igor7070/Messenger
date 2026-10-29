package com.example.unlimitedpossibilities12.messenger;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.User;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class GroupCallUserSelectionDialogFragment extends DialogFragment {
    private static final String TAG = "GroupCallUserSelectionDialog";
    private RecyclerView usersRecyclerView;
    private EditText searchEditText;
    private MaterialButton startCallButton;
    private SelectionUsersAdapter usersAdapter;
    private List<User> usersList = new ArrayList<>();
    private List<User> selectedUsers = new ArrayList<>();
    private ApiService apiService;
    private OnStartGroupCallListener startGroupCallListener;
    private int currentUserId;

    public interface OnStartGroupCallListener {
        void onStartGroupCall(List<User> selectedUsers, int initiatorId, String initiatorName);
    }

    public static GroupCallUserSelectionDialogFragment newInstance(int currentUserId) {
        GroupCallUserSelectionDialogFragment fragment = new GroupCallUserSelectionDialogFragment();
        Bundle args = new Bundle();
        args.putInt("currentUserId", currentUserId);
        fragment.setArguments(args);
        return fragment;
    }

    public void setOnStartGroupCallListener(OnStartGroupCallListener listener) {
        this.startGroupCallListener = listener;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(DialogFragment.STYLE_NORMAL, R.style.DialogStyle);
        apiService = ApiClient.getApiService();
        if (getArguments() != null) {
            currentUserId = getArguments().getInt("currentUserId", -1);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_group_call_user_selection, container, false);

        usersRecyclerView = view.findViewById(R.id.users_recycler_view);
        searchEditText = view.findViewById(R.id.search_users_edit_text);
        startCallButton = view.findViewById(R.id.start_call_button);

        usersRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        usersAdapter = new SelectionUsersAdapter(user -> {
            if (selectedUsers.contains(user)) {
                selectedUsers.remove(user);
            } else if (selectedUsers.size() < 4) {
                selectedUsers.add(user);
            } else {
                Toast.makeText(requireContext(), "Maximum 4 users can be selected (initiator included)", Toast.LENGTH_LONG).show();
                return;
            }
            usersAdapter.updateSelectedUsers(selectedUsers);
            updateStartButtonState();
        });
        usersRecyclerView.setAdapter(usersAdapter);

        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                filterUsers(s.toString());
            }
        });

        startCallButton.setOnClickListener(v -> {
            if (startGroupCallListener != null && !selectedUsers.isEmpty()) {
                String initiatorName = getContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
                        .getString("username", "Unknown");
                startGroupCallListener.onStartGroupCall(selectedUsers, currentUserId, initiatorName);
                dismiss();
            } else {
                Toast.makeText(getContext(), "Select at least one user", Toast.LENGTH_SHORT).show();
            }
        });

        fetchUsers();
        updateStartButtonState();

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            // Устанавливаем ширину 80% от экрана
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.8);
            // Ограничиваем максимальную высоту диалога
            int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.6); // 60% высоты экрана
            dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().getAttributes().height = maxHeight; // Устанавливаем максимальную высоту
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_background);
        }
    }

    private void fetchUsers() {
        apiService.getUsers().enqueue(new Callback<List<User>>() {
            @Override
            public void onResponse(Call<List<User>> call, Response<List<User>> response) {
                if (response.isSuccessful() && response.body() != null) {
                    usersList.clear();
                    for (User user : response.body()) {
                        if (user.getId() != currentUserId) {
                            usersList.add(user);
                        }
                    }
                    usersAdapter.updateUsers(usersList);
                } else {
                    Toast.makeText(getContext(), "Failed to fetch users", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<List<User>> call, Throwable t) {
                Toast.makeText(getContext(), "Error fetching users", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void filterUsers(String term) {
        List<User> filteredList = new ArrayList<>();
        for (User user : usersList) {
            if (user.getUsername().toLowerCase().contains(term.toLowerCase())) {
                filteredList.add(user);
            }
        }
        usersAdapter.updateUsers(filteredList);
    }

    private void updateStartButtonState() {
        startCallButton.setEnabled(!selectedUsers.isEmpty());
    }
}
