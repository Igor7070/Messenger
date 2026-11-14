package com.example.unlimitedpossibilities12.messenger;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
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

/**
 * Свой профиль (вкладка Profile).
 *
 * Порядок теперь такой же, как в профиле собеседника: снимок во весь
 * верх, имя и присутствие поверх него, ряд плиток, карточка сведений.
 * Снимок выбирается кружком с камерой и уходит на сервер сразу, описание —
 * по карандашу в карточке; ярких кнопок «Update Profile» и «Personal Chats»
 * больше нет: свои чаты стали плиткой, выход и удаление учётной записи
 * живут в трёх точках шапки.
 */
public class ProfileFragment extends Fragment {

    private static final String TAG = "ProfileFragment";

    private TextView usernameTextView, avatarTextView, bioTextView, statusTextView, usernameHandle;
    private ImageView avatarImageView, bioEditIcon, avatarInputButton;
    /** Плитки действий и точка присутствия — обычные вьюхи, не кнопки. */
    private View statusIndicator, personalChatsButton, inviteTile, assistantTile;
    private androidx.core.widget.NestedScrollView profileScroll;
    private View bioCard;

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

        // Режим ставим до появления клавиатуры: иначе система сдвигает окно
        // вверх, и карточка сведений остаётся под клавиатурой
        requireActivity().getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

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
        bioEditIcon = view.findViewById(R.id.bio_edit_icon);
        avatarInputButton = view.findViewById(R.id.avatar_input_button);
        personalChatsButton = view.findViewById(R.id.personal_chats_button);
        inviteTile = view.findViewById(R.id.invite_tile);
        assistantTile = view.findViewById(R.id.assistant_tile);
        statusTextView = view.findViewById(R.id.status_text_view);
        statusIndicator = view.findViewById(R.id.status_indicator);
        usernameHandle = view.findViewById(R.id.username_handle);
        profileScroll = view.findViewById(R.id.profile_scroll);
        bioCard = view.findViewById(R.id.bio_card);

        avatarInputButton.setOnClickListener(v -> openFilePicker());
        personalChatsButton.setOnClickListener(v -> handlePersonalChats());
        bioEditIcon.setOnClickListener(v -> startBioEditing());
        // Описание правится и по самой строке, а не только по карандашу
        bioTextView.setOnClickListener(v -> startBioEditing());

        // Пригласить — то же системное окно «поделиться», что на экране людей
        inviteTile.setOnClickListener(v -> {
            if (requireActivity() instanceof MessengerMainActivity) {
                ((MessengerMainActivity) requireActivity()).shareInvite();
            }
        });

        // Ассистент нужен из любого места, здесь он — третья плитка
        assistantTile.setOnClickListener(v -> {
            if (requireActivity() instanceof MessengerMainActivity) {
                ((MessengerMainActivity) requireActivity()).openAssistant();
            }
        });
        ImageView profileMenuButton = requireActivity().findViewById(R.id.profile_menu_button);
        if (profileMenuButton != null) {
            profileMenuButton.setVisibility(View.VISIBLE);
            profileMenuButton.setOnClickListener(v -> showSettingsMenu());
        }

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
        if (currentUser == null || !isAdded()) return;

        String name = currentUser.getUsername() == null ? "" : currentUser.getUsername();
        usernameTextView.setText(name);
        usernameHandle.setText("@" + name);

        // Своя страница открыта — значит мы в сети; раньше строка присутствия
        // вообще никогда не заполнялась
        statusTextView.setText("Online");
        statusIndicator.setVisibility(View.VISIBLE);

        String bio = currentUser.getBio();
        boolean hasBio = bio != null && !bio.trim().isEmpty();
        bioTextView.setText(hasBio ? bio : getString(R.string.profile_no_bio));
        bioTextView.setTextColor(hasBio ? 0xFFE2E8F0 : 0xFF64748B);

        String letter = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        avatarTextView.setText(letter);

        // Аватар + обработчик открытия полного изображения
        if (currentUser.getAvatar() != null && !currentUser.getAvatar().isEmpty()) {
            String avatarUrl = "https://unlimitedpossibilities12.org" + currentUser.getAvatar()
                    + "?t=" + System.currentTimeMillis();

            Glide.with(this)
                    .load(avatarUrl)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .centerCrop()
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                    Target<Drawable> target, boolean isFirstResource) {
                            // Снимок может не открыться — тогда буква имени,
                            // а не пустой тёмный прямоугольник во весь верх
                            if (!isAdded()) return true;
                            avatarImageView.setImageDrawable(null);
                            avatarTextView.setVisibility(View.VISIBLE);
                            return true;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model,
                                                       Target<Drawable> target, DataSource dataSource,
                                                       boolean isFirstResource) {
                            if (isAdded()) avatarTextView.setVisibility(View.GONE);
                            return false;
                        }
                    })
                    .into(avatarImageView);

            // ←←← КЛИК ПО АВАТАРУ ←←←
            avatarImageView.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), FullScreenImageActivity.class);
                intent.putExtra("imageUrl", avatarUrl);
                intent.putExtra("username", currentUser.getUsername());
                startActivity(intent);
            });

        } else {
            // Заглушка с первой буквой
            avatarImageView.setImageDrawable(null);
            avatarTextView.setVisibility(View.VISIBLE);

            // Снимка нет — открывать нечего, снимок выбирается кружком с камерой
            avatarImageView.setOnClickListener(null);
        }
    }

    /**
     * Правка описания отдельным окном: в самой карточке клавиатура
     * закрывала строку почти целиком — этот экран система не сжимает, а сдвигает.
     */
    private void startBioEditing() {
        if (!isAdded()) return;

        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_edit_bio, null, false);
        EditText input = content.findViewById(R.id.bio_input);

        String bio = currentUser == null || currentUser.getBio() == null ? "" : currentUser.getBio();
        input.setText(bio);
        input.setSelection(bio.length());

        androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setView(content)
                        .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }

        content.findViewById(R.id.bio_cancel).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.bio_save).setOnClickListener(v -> {
            saveBio(input.getText().toString().trim());
            dialog.dismiss();
        });

        dialog.show();
        input.requestFocus();
    }

    private void saveBio(String typed) {
        if (currentUser != null) currentUser.setBio(typed);

        bioTextView.setText(typed.isEmpty() ? getString(R.string.profile_no_bio) : typed);
        bioTextView.setTextColor(typed.isEmpty() ? 0xFF64748B : 0xFFE2E8F0);

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

        // Берём само описание: в строке может стоять подсказка,
        // и прежде она уходила на сервер как описание
        String bioText = currentUser.getBio() == null ? "" : currentUser.getBio().trim();

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
                avatarTextView.setVisibility(View.GONE);
                Glide.with(this).load(newAvatarUri).centerCrop().into(avatarImageView);
                // Снимок уходит сразу: отдельной кнопки «обновить» больше нет
                handleUpdateUser();
            }
        }
    }

    @Override
    public void onDestroyView() {
        requireActivity().getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED);
        ImageView profileMenuButton = requireActivity().findViewById(R.id.profile_menu_button);
        if (profileMenuButton != null) {
            profileMenuButton.setVisibility(View.GONE);
        }
        super.onDestroyView();
    }
}