package com.example.unlimitedpossibilities12.mainPage;

import android.content.Intent;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.communication_with_ai.ChatWithAIActivity;
import com.example.unlimitedpossibilities12.job_search.MainActivity;
import com.example.unlimitedpossibilities12.messenger.MessengerMainActivity;

public class WebViewMainPageActivity extends AppCompatActivity {
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webview_main_page);

        webView = findViewById(R.id.webview);
        webView.setWebViewClient(new MyWebViewClient());
        webView.getSettings().setJavaScriptEnabled(true);
        webView.loadUrl("https://unlimitedpossibilities12.org");
    }

    private class MyWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            // Здесь перехватываем URL и направляем на соответствующую активность
            if (url.equals("https://unlimitedpossibilities12.org/convenient_job_search")) {
                Intent intent = new Intent(WebViewMainPageActivity.this, MainActivity.class);
                startActivity(intent);
                return true; // Указываем, что мы обработали это событие
            } else if (url.equals("https://unlimitedpossibilities12.org/communicating_with_a_primitive_ai_model")) {
                Intent intent = new Intent(WebViewMainPageActivity.this, ChatWithAIActivity.class);
                startActivity(intent);
                return true;
            } else if (url.equals("https://unlimitedpossibilities12.org/messenger")) {
                Intent intent = new Intent(WebViewMainPageActivity.this, MessengerMainActivity.class);
                startActivity(intent);
                return true;
            }
            // Для остальных URL просто загружаем в WebView
            view.loadUrl(url);
            return false;
        }
    }
}
