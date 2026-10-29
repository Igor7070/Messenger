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

        ImageView fullImageView = findViewById(R.id.full_message_image_view);
        ImageButton downloadButton = findViewById(R.id.download_message_image_button);

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
                Toast.makeText(this, "Локальный файл не найден", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(this, "Локальный файл не найден", Toast.LENGTH_SHORT).show();
                }
            } else if (imageUrl != null && !imageUrl.isEmpty()) {
                try {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(imageUrl));
                    startActivity(browserIntent);
                } catch (Exception e) {
                    Toast.makeText(this, "Cannot download image", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Нет данных для скачивания", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(this, "Фото сохранено в Downloads", Toast.LENGTH_LONG).show();
                    Log.d("FullScreen", "Сохранено в Downloads: " + fileName);
                } else {
                    Toast.makeText(this, "Не удалось создать файл в Downloads", Toast.LENGTH_SHORT).show();
                }
            } else {
                // Для старых версий Android — просто тост (или реализуй старый способ)
                Toast.makeText(this, "Сохранение в Downloads доступно с Android 10+", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка сохранения: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            Log.e("FullScreen", "Ошибка сохранения в Downloads", e);
        }
    }
}
