package com.example.unlimitedpossibilities12.messenger;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ListPopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.messenger.popupMenu.PopupMenuAdapter;
import com.example.unlimitedpossibilities12.messenger.popupMenu.PopupMenuItem;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class ProfileFragment extends Fragment {

    private static final String TAG = "ProfileFragment";

    private TextView usernameTextView, avatarTextView, bioTextView, statusTextView;
    private ImageView avatarImageView, menuButton, bioEditIcon;
    private EditText bioEditText;
    private com.google.android.material.button.MaterialButton updateButton, personalChatsButton, avatarInputButton;
    private LinearLayout currentUserButtons;

    private ApiService apiService;
    private int currentUserId;
    private User currentUser;
    private Uri newAvatarUri;

    public static ProfileFragment newInstance(int currentUserId) {
        ProfileFragment fragment = new ProfileFragment();
        Bundle args = new Bundle();
        args.putInt("currentUserId", currentUserId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        apiService = ApiClient.getApiService();

        if (getArguments() != null) {
            currentUserId = getArguments().getInt("currentUserId", -1);
        }

        initViews(view);
        if (currentUserId != -1) {
            loadUserData();
        }
    }

    private void initViews(View view) {
        usernameTextView = view.findViewById(R.id.username_text_view);
        avatarImageView = view.findViewById(R.id.avatar_image_view);
        avatarTextView = view.findViewById(R.id.avatar_text_view);
        bioTextView = view.findViewById(R.id.bio_text_view);
        bioEditText = view.findViewById(R.id.bio_edit_text);
        bioEditIcon = view.findViewById(R.id.bio_edit_icon);
        avatarInputButton = view.findViewById(R.id.avatar_input_button);
        updateButton = view.findViewById(R.id.update_button);
        personalChatsButton = view.findViewById(R.id.personal_chats_button);
        currentUserButtons = view.findViewById(R.id.current_user_buttons);
        //menuButton = view.findViewById(R.id.menu_button);
        statusTextView = view.findViewById(R.id.status_text_view);

        avatarInputButton.setOnClickListener(v -> openFilePicker());
        updateButton.setOnClickListener(v -> handleUpdateUser());
        personalChatsButton.setOnClickListener(v -> handlePersonalChats());
        bioEditIcon.setOnClickListener(v -> startBioEditing());
        ImageView profileMenuButton = requireActivity().findViewById(R.id.profile_menu_button);
        if (profileMenuButton != null) {
            profileMenuButton.setVisibility(View.VISIBLE);
            profileMenuButton.setOnClickListener(v -> showSettingsMenu());
        }

        bioEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveBio();
                return true;
            }
            return false;
        });
    }

    private void loadUserData() {
        apiService.getUserById(currentUserId).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    currentUser = response.body();
                    displayUserData();
                } else {
                    Toast.makeText(requireContext(), "Failed to load profile", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Toast.makeText(requireContext(), "Error loading profile", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void displayUserData() {
        if (currentUser == null) return;

        usernameTextView.setText(currentUser.getUsername());
        bioTextView.setText(currentUser.getBio() != null && !currentUser.getBio().isEmpty()
                ? currentUser.getBio() : "No bio yet");

        // Аватар + обработчик открытия полного изображения
        if (currentUser.getAvatar() != null && !currentUser.getAvatar().isEmpty()) {
            String avatarUrl = "https://unlimitedpossibilities12.org" + currentUser.getAvatar()
                    + "?t=" + System.currentTimeMillis();

            Glide.with(this)
                    .load(avatarUrl)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .circleCrop()
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(avatarImageView);

            avatarTextView.setVisibility(View.GONE);

            // ←←← КЛИК ПО АВАТАРУ ←←←
            avatarImageView.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), FullScreenImageActivity.class);
                intent.putExtra("imageUrl", avatarUrl);
                intent.putExtra("username", currentUser.getUsername());
                startActivity(intent);
            });

        } else {
            // Заглушка с первой буквой
            avatarTextView.setText(currentUser.getUsername().substring(0, 1).toUpperCase());
            avatarTextView.setVisibility(View.VISIBLE);

            // Клик по заглушке тоже открывает FullScreen (можно показать просто имя)
            avatarImageView.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), FullScreenImageActivity.class);
                intent.putExtra("username", currentUser.getUsername());
                startActivity(intent);
            });
        }
    }

    private void startBioEditing() {
        bioTextView.setVisibility(View.GONE);
        bioEditText.setVisibility(View.VISIBLE);
        bioEditText.setText(bioTextView.getText());
        bioEditText.requestFocus();
    }

    private void saveBio() {
        bioEditText.setVisibility(View.GONE);
        bioTextView.setVisibility(View.VISIBLE);
        bioTextView.setText(bioEditText.getText());

        // Сразу сохраняем на сервер
        handleUpdateUser();
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        startActivityForResult(intent, 101);
    }

    private void handleUpdateUser() {
        if (currentUser == null) return;

        // Правильно определяем текущее био
        String bioText;
        if (bioEditText.getVisibility() == View.VISIBLE) {
            bioText = bioEditText.getText().toString().trim();
        } else {
            bioText = bioTextView.getText().toString().trim();
        }

        RequestBody bioBody = RequestBody.create(MediaType.parse("text/plain"), bioText);

        MultipartBody.Part avatarPart = null;
        if (newAvatarUri != null) {
            try {
                InputStream inputStream = requireContext().getContentResolver().openInputStream(newAvatarUri);
                byte[] bytes = new byte[inputStream.available()];
                inputStream.read(bytes);
                inputStream.close();

                RequestBody avatarBody = RequestBody.create(MediaType.parse("image/*"), bytes);
                avatarPart = MultipartBody.Part.createFormData("avatar", "avatar.jpg", avatarBody);
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Error loading avatar: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }
        }

        apiService.updateUser(currentUserId, bioBody, avatarPart).enqueue(new Callback<User>() {
            @Override
            public void onResponse(Call<User> call, Response<User> response) {
                if (response.isSuccessful() && response.body() != null) {
                    newAvatarUri = null;

                    // Скрываем поле редактирования, если оно было открыто
                    if (bioEditText.getVisibility() == View.VISIBLE) {
                        bioEditText.setVisibility(View.GONE);
                        bioTextView.setVisibility(View.VISIBLE);
                    }

                    Toast.makeText(requireContext(), "Profile updated successfully!", Toast.LENGTH_SHORT).show();

                    // Уведомляем главную активность об обновлении профиля
                    Intent intent = new Intent("PROFILE_UPDATED");
                    intent.putExtra("userId", currentUserId);
                    LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent);

                    loadUserData(); // Перезагружаем актуальные данные с сервера
                } else {
                    Toast.makeText(requireContext(), "Failed to update profile", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<User> call, Throwable t) {
                Toast.makeText(requireContext(), "Error updating profile: " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handlePersonalChats() {
        Intent intent = new Intent(requireContext(), PersonalChatsActivity.class);
        intent.putExtra("currentUserId", currentUserId);
        startActivity(intent);
    }

    private void showSettingsMenu() {
        ImageView anchor = requireActivity().findViewById(R.id.profile_menu_button);
        if (anchor == null) {
            Toast.makeText(requireContext(), "Menu anchor not found", Toast.LENGTH_SHORT).show();
            return;
        }

        ListPopupWindow listPopupWindow = new ListPopupWindow(requireContext());
        listPopupWindow.setAnchorView(anchor);
        listPopupWindow.setModal(true);
        listPopupWindow.setDropDownGravity(Gravity.END);
        listPopupWindow.setWidth(550);
        listPopupWindow.setVerticalOffset(4);

        // Важно: тот же фон, что и в UserProfileActivity
        listPopupWindow.setBackgroundDrawable(requireContext().getDrawable(R.drawable.popup_menu_background));

        List<PopupMenuItem> menuItems = new ArrayList<>();
        // Выход живёт здесь, а не в шапке списка чатов, как было раньше
        menuItems.add(new PopupMenuItem("Log out", null, Color.parseColor("#E2E8F0")));
        menuItems.add(new PopupMenuItem("Delete account", null, Color.parseColor("#E2E8F0")));

        PopupMenuAdapter adapter = new PopupMenuAdapter(requireContext(), menuItems);
        listPopupWindow.setAdapter(adapter);

        listPopupWindow.setOnItemClickListener((parent, view, position, id) -> {
            String title = menuItems.get(position).title;

            if ("Log out".equals(title)) {
                if (requireActivity() instanceof MessengerMainActivity) {
                    ((MessengerMainActivity) requireActivity()).logoutAndReturnToAuth();
                }
            } else if ("Delete account".equals(title)) {
                showDeleteAccountDialog();
            }
            listPopupWindow.dismiss();
        });

        listPopupWindow.show();
    }

    private void showDeleteAccountDialog() {
        DeleteAccountDialogFragment dialog = new DeleteAccountDialogFragment(() -> handleDeleteAccount());
        dialog.show(getParentFragmentManager(), "DeleteAccountDialog");
    }

    private void handleDeleteAccount() {
        apiService.deleteUser(currentUserId).enqueue(new Callback<okhttp3.ResponseBody>() {
            @Override
            public void onResponse(Call<okhttp3.ResponseBody> call, Response<okhttp3.ResponseBody> response) {
                if (response.isSuccessful()) {
                    requireActivity().getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
                            .edit()
                            .remove("userId")
                            .remove("username")
                            .apply();
                    // Аккаунта больше нет — токен доступа тоже ни к чему
                    com.example.unlimitedpossibilities12.api.AuthToken.clear(requireContext());

                    Toast.makeText(requireContext(), "Account deleted successfully!", Toast.LENGTH_LONG).show();

                    Intent intent = new Intent(requireContext(), MessengerMainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    requireActivity().finish();
                } else {
                    Toast.makeText(requireContext(), "Failed to delete account", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<okhttp3.ResponseBody> call, Throwable t) {
                Toast.makeText(requireContext(), "Error deleting account", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 101 && resultCode == requireActivity().RESULT_OK && data != null) {
            newAvatarUri = data.getData();
            if (newAvatarUri != null) {
                Glide.with(this).load(newAvatarUri).circleCrop().into(avatarImageView);
            }
        }
    }

    @Override
    public void onDestroyView() {
        ImageView profileMenuButton = requireActivity().findViewById(R.id.profile_menu_button);
        if (profileMenuButton != null) {
            profileMenuButton.setVisibility(View.GONE);
        }
        super.onDestroyView();
    }
}