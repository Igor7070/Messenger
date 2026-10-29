package com.example.unlimitedpossibilities12.messenger;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.unlimitedpossibilities12.R;
import com.github.chrisbanes.photoview.PhotoView;

public class FullScreenImageActivity extends AppCompatActivity {

    private static final String TAG = "FullScreenImageActivity";
    private static final String KEY_IMAGE_URL = "imageUrl";
    private static final String KEY_USERNAME = "username";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen_image);

        PhotoView fullScreenImageView = findViewById(R.id.full_screen_image_view);
        TextView fullScreenAvatarTextView = findViewById(R.id.full_screen_avatar_text_view);

        // Получаем данные из Intent или сохраненного состояния
        String imageUrl;
        String username;
        if (savedInstanceState != null) {
            imageUrl = savedInstanceState.getString(KEY_IMAGE_URL);
            username = savedInstanceState.getString(KEY_USERNAME);
            Log.d(TAG, "Restored imageUrl: " + imageUrl + ", username: " + username);
        } else {
            imageUrl = getIntent().getStringExtra("imageUrl");
            username = getIntent().getStringExtra("username");
            Log.d(TAG, "Received imageUrl: " + imageUrl + ", username: " + username);
        }

        // Загружаем изображение и обрабатываем дефолтный аватар
        if (imageUrl != null && !imageUrl.isEmpty()) {
            Glide.with(this)
                    .load(imageUrl)
                    .error(R.drawable.default_messenger_avatar)
                    .into(fullScreenImageView);
            fullScreenAvatarTextView.setVisibility(View.GONE);
        } else {
            Glide.with(this)
                    .load(R.drawable.default_messenger_avatar)
                    .into(fullScreenImageView);
            if (username != null && !username.isEmpty()) {
                fullScreenAvatarTextView.setText(username.substring(0, 1).toUpperCase());
                fullScreenAvatarTextView.setVisibility(View.VISIBLE);
            } else {
                fullScreenAvatarTextView.setVisibility(View.GONE);
                Log.w(TAG, "Username is null or empty, hiding avatar text");
            }
        }

        // Настраиваем обработку одиночного нажатия для закрытия активности
        fullScreenImageView.setOnPhotoTapListener((view, x, y) -> {
            Log.d(TAG, "Photo tapped, finishing activity");
            finish();
        });

        // Настраиваем обработку долгого нажатия (опционально)
        fullScreenImageView.setOnLongClickListener(v -> {
            Log.d(TAG, "Long press on photo");
            return true;
        });

        // Устанавливаем параметры масштаба
        fullScreenImageView.setMaximumScale(10.0f);
        fullScreenImageView.setMediumScale(3.0f);
        fullScreenImageView.setMinimumScale(1.0f);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Сохраняем URL изображения и username при повороте
        String imageUrl = getIntent().getStringExtra("imageUrl");
        String username = getIntent().getStringExtra("username");
        outState.putString(KEY_IMAGE_URL, imageUrl);
        outState.putString(KEY_USERNAME, username);
        Log.d(TAG, "Saved imageUrl: " + imageUrl + ", username: " + username);
    }
}
