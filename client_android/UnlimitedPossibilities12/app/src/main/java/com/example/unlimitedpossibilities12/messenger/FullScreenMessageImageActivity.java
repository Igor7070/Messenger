package com.example.unlimitedpossibilities12.messenger;

import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.unlimitedpossibilities12.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

public class FullScreenMessageImageActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_full_screen_message_image);

        com.github.chrisbanes.photoview.PhotoView fullImageView = findViewById(R.id.full_message_image_view);
        ImageView downloadButton = findViewById(R.id.download_message_image_button);
        ImageView shareButton = findViewById(R.id.share_message_image_button);
        ImageView closeButton = findViewById(R.id.close_image_button);

        // Выйти можно было только системной кнопкой «назад»
        closeButton.setOnClickListener(v -> finish());
        // Нажатие по картинке тоже закрывает — как в телеграме
        fullImageView.setOnPhotoTapListener((view, x, y) -> finish());
        // Под просмотром картинки верхняя строка тоже чёрная
        getWindow().setStatusBarColor(android.graphics.Color.BLACK);

        // Получаем extras
        String imageUrl = getIntent().getStringExtra("message_image_url");
        String localPath = getIntent().getStringExtra("local_file_path");

        Log.d("FullScreen", "Получены extras: url=" + imageUrl + ", localPath=" + localPath);

        // Загрузка изображения
        if (localPath != null && !localPath.isEmpty()) {
            File file = new File(localPath);
            if (file.exists()) {
                Glide.with(this)
                        .load(file)
                        .placeholder(R.drawable.bg_message_image_placeholder)
                        .error(R.drawable.ic_error)
                        .into(fullImageView);
                Log.d("FullScreen", "Загружено из локального файла: " + localPath);
            } else {
                Toast.makeText(this, "Local file not found", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
        } else if (imageUrl != null && !imageUrl.isEmpty()) {
            Glide.with(this)
                    .load(imageUrl)
                    .placeholder(R.drawable.bg_message_image_placeholder)
                    .error(R.drawable.ic_error)
                    .into(fullImageView);
            Log.d("FullScreen", "Загружено по URL: " + imageUrl);
        } else {
            Toast.makeText(this, "No image URL or path provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Кнопка скачивания — теперь работает и для локального файла
        downloadButton.setOnClickListener(v -> {
            if (localPath != null && !localPath.isEmpty()) {
                File file = new File(localPath);
                if (file.exists()) {
                    saveToDownloads(file, "downloaded_image.jpg", "image/jpeg");
                } else {
                    Toast.makeText(this, "Local file not found", Toast.LENGTH_SHORT).show();
                }
            } else if (imageUrl != null && !imageUrl.isEmpty()) {
                try {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(imageUrl));
                    startActivity(browserIntent);
                } catch (Exception e) {
                    Toast.makeText(this, "Cannot download image", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Nothing to download", Toast.LENGTH_SHORT).show();
            }
        });

        // Поделиться: прежде картинку можно было только скачать
        shareButton.setOnClickListener(v -> {
            try {
                if (localPath != null && !localPath.isEmpty()) {
                    File file = new File(localPath);
                    Uri shared = androidx.core.content.FileProvider.getUriForFile(
                            this, getPackageName() + ".fileprovider", file);
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("image/*");
                    intent.putExtra(Intent.EXTRA_STREAM, shared);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(intent, getString(R.string.image_viewer_share)));
                } else if (imageUrl != null && !imageUrl.isEmpty()) {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, imageUrl);
                    startActivity(Intent.createChooser(intent, getString(R.string.image_viewer_share)));
                }
            } catch (Exception e) {
                Toast.makeText(this, "Could not share the photo", Toast.LENGTH_SHORT).show();
                Log.e("FullScreen", "Поделиться не вышло", e);
            }
        });
    }

    // Метод сохранения в Downloads (добавь его в класс)
    private void saveToDownloads(File sourceFile, String fileName, String mimeType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri);
                         FileInputStream in = new FileInputStream(sourceFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                    Toast.makeText(this, "Photo saved to Downloads", Toast.LENGTH_LONG).show();
                    Log.d("FullScreen", "Сохранено в Downloads: " + fileName);
                } else {
                    Toast.makeText(this, "Could not create the file in Downloads", Toast.LENGTH_SHORT).show();
                }
            } else {
                // Для старых версий Android — просто тост (или реализуй старый способ)
                Toast.makeText(this, "Saving to Downloads needs Android 10 or newer", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not save: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("FullScreen", "Ошибка сохранения в Downloads", e);
        }
    }
}
