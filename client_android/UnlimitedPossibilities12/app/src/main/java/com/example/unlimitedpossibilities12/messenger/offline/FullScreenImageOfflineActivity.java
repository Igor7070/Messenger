package com.example.unlimitedpossibilities12.messenger.offline;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.example.unlimitedpossibilities12.R;

import java.io.InputStream;
import java.io.OutputStream;

public class FullScreenImageOfflineActivity extends AppCompatActivity {

    public static final String EXTRA_IMAGE_URI = "image_uri";

    public static void open(android.content.Context context, Uri imageUri) {
        Intent intent = new Intent(context, FullScreenImageOfflineActivity.class);
        intent.putExtra(EXTRA_IMAGE_URI, imageUri.toString());
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_image_offline);

        ImageView imageView = findViewById(R.id.fullscreen_image);
        com.google.android.material.floatingactionbutton.FloatingActionButton btnSave =
                findViewById(R.id.btn_save);

        String uriString = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (uriString != null) {
            Uri uri = Uri.parse(uriString);
            Glide.with(this)
                    .load(uri)
                    .into(imageView);
        } else {
            Toast.makeText(this, "Image not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Клик по изображению — закрыть
        imageView.setOnClickListener(v -> finish());

        // Кнопка сохранения
        btnSave.setOnClickListener(v -> saveImageToGallery());
    }

    private void saveImageToGallery() {
        String uriString = getIntent().getStringExtra(EXTRA_IMAGE_URI);
        if (uriString == null) return;

        Uri sourceUri = Uri.parse(uriString);
        String fileName = "UP12_Offline_" + System.currentTimeMillis() + ".jpg";

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Messenger UP12");

            ContentResolver resolver = getContentResolver();
            Uri targetUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (targetUri != null) {
                try (InputStream in = resolver.openInputStream(sourceUri);
                     OutputStream out = resolver.openOutputStream(targetUri)) {

                    if (in != null && out != null) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                    }
                }
                Toast.makeText(this, "Image saved to Gallery", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
}
