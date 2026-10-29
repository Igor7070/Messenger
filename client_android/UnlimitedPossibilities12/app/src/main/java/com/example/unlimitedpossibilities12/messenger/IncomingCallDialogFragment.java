package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.CallRequest;
import com.example.unlimitedpossibilities12.api.GroupCallRequest;
import com.google.android.material.button.MaterialButton;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class IncomingCallDialogFragment extends DialogFragment {
    private static final String TAG = "IncomingCallDialog"; // [Добавлено]: TAG для логирования
    private CallRequest callRequest;
    private GroupCallRequest groupCallRequest;
    private Runnable onAccept, onReject;
    private boolean isActionTaken = false;
    private boolean hasSavedCall = false; // [Добавлено]: Флаг для предотвращения дублирования сохранения

    public IncomingCallDialogFragment(CallRequest callRequest, Runnable onAccept, Runnable onReject) {
        this.callRequest = callRequest;
        this.onAccept = onAccept;
        this.onReject = onReject;
    }

    public IncomingCallDialogFragment(GroupCallRequest groupCallRequest, Runnable onAccept, Runnable onReject) {
        this.groupCallRequest = groupCallRequest;
        this.onAccept = onAccept;
        this.onReject = onReject;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setCancelable(false);
        setStyle(STYLE_NO_TITLE, R.style.FullScreenDialog);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_incoming_call, container, false);

        // UI элементы
        TextView callTitleTextView = view.findViewById(R.id.call_title);
        TextView callerNameTextView = view.findViewById(R.id.caller_name_text_view);
        ImageView callTypeIcon = view.findViewById(R.id.call_type_icon);
        ImageView callIcon = view.findViewById(R.id.call_icon);
        MaterialButton acceptButton = view.findViewById(R.id.accept_button);
        MaterialButton rejectButton = view.findViewById(R.id.reject_button);

        // Установка заголовка и иконки типа звонка
        String callType, callerName, avatarUrl = null;
        if (callRequest != null) {
            callType = callRequest.getType() != null ? callRequest.getType() : "voice";
            callerName = callRequest.getCallerName() != null ? callRequest.getCallerName() : "User " + callRequest.getCallerId();
            avatarUrl = callRequest.getCallerAvatar();
            switch (callType) {
                case "video":
                    callTitleTextView.setText("Incoming Video Call");
                    callTypeIcon.setImageResource(R.drawable.ic_video_call);
                    callTypeIcon.setColorFilter(Color.parseColor("#0288D1")); // Синий цвет для контраста
                    break;
                case "translate":
                    callTitleTextView.setText("Incoming Voice Call Translation");
                    callTypeIcon.setImageResource(R.drawable.ic_translate_call);
                    callTypeIcon.setColorFilter(Color.parseColor("#0288D1")); // Синий цвет для контраста
                    break;
                case "voice_secure":
                    callTitleTextView.setText("Incoming Secure Call (E2EE)");
                    callTypeIcon.setImageResource(R.drawable.ic_lock); // ← иконка с замком
                    callTypeIcon.setColorFilter(Color.parseColor("#00C853")); // зелёный — как в Signal
                    break;
                case "voice":
                default:
                    callTitleTextView.setText("Incoming Voice Call");
                    callTypeIcon.setImageResource(android.R.drawable.stat_sys_phone_call);
                    callTypeIcon.setColorFilter(Color.parseColor("#0288D1")); // Синий цвет для контраста
                    break;
            }
        } else {
            callType = "group";
            callerName = groupCallRequest.getInitiatorName() != null ? groupCallRequest.getInitiatorName() : "User " + groupCallRequest.getInitiatorId();
            callTitleTextView.setText("Incoming Group Call");
            callTypeIcon.setImageResource(R.drawable.ic_group_call); // Используйте свою иконку для группового звонка
            callTypeIcon.setColorFilter(Color.parseColor("#0288D1")); // Синий цвет для контраста
        }

        callerNameTextView.setText("From " + callerName);

        // Создаем плейсхолдер с первой буквой имени
        String firstLetter = callerName.substring(0, 1).toUpperCase();
        Bitmap bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setColor(getRandomColor(callerName));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(40, 40, 40, paint);
        paint.setColor(Color.WHITE);
        paint.setTextSize(40);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(firstLetter, 40, 56, paint);

        // Преобразуем Bitmap в Drawable
        Resources resources = requireContext().getResources();
        BitmapDrawable placeholderDrawable = new BitmapDrawable(resources, bitmap);

        // Загрузка аватара
        if (callRequest != null && avatarUrl != null && !avatarUrl.isEmpty()) {
            String fullAvatarUrl = "https://unlimitedpossibilities12.org" + avatarUrl + "?t=" + System.currentTimeMillis();
            Glide.with(this)
                    .load(fullAvatarUrl)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
                    .circleCrop()
                    .placeholder(placeholderDrawable)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(callIcon);
        } else if (groupCallRequest != null) {
            // Устанавливаем плейсхолдер сразу для группового вызова
            callIcon.setImageDrawable(placeholderDrawable);
            // Загружаем аватар через API для группового вызова
            new Thread(() -> {
                try {
                    OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
                    Request request = new Request.Builder()
                            .url("https://unlimitedpossibilities12.org/api/users/" + Integer.parseInt(groupCallRequest.getInitiatorId()))
                            .get()
                            .build();
                    Response response = client.newCall(request).execute();
                    if (response.isSuccessful() && response.body() != null) {
                        JSONObject json = new JSONObject(response.body().string());
                        String groupAvatarUrl = json.optString("avatar");
                        if (!groupAvatarUrl.isEmpty()) {
                            String fullAvatarUrl = "https://unlimitedpossibilities12.org" + groupAvatarUrl + "?t=" + System.currentTimeMillis();
                            requireActivity().runOnUiThread(() -> {
                                Glide.with(IncomingCallDialogFragment.this)
                                        .load(fullAvatarUrl)
                                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                                        .skipMemoryCache(true)
                                        .circleCrop()
                                        .placeholder(placeholderDrawable)
                                        .transition(DrawableTransitionOptions.withCrossFade())
                                        .into(callIcon);
                                Log.d(TAG, "Group call initiator avatar loaded: " + groupAvatarUrl);
                            });
                        } else {
                            Log.d(TAG, "No avatar found in response for group call, using placeholder");
                        }
                    } else {
                        Log.e(TAG, "Failed to fetch initiator avatar: HTTP " + response.code());
                    }
                } catch (IOException | JSONException | NumberFormatException e) {
                    Log.e(TAG, "Error fetching initiator avatar: " + e.getMessage());
                }
            }).start();
        } else {
            callIcon.setImageDrawable(placeholderDrawable);
        }

        // Анимация иконки
        Animation pulseAnimation = AnimationUtils.loadAnimation(requireContext(), R.anim.pulse);
        callIcon.startAnimation(pulseAnimation);

        // Вибрация
        Vibrator vibrator = (Vibrator) requireContext().getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(500);
            }
        }

        // Обработчики кнопок
        acceptButton.setOnClickListener(v -> {
            isActionTaken = true;
            onAccept.run();
            dismissAllowingStateLoss();
        });

        rejectButton.setOnClickListener(v -> {
            isActionTaken = true;
            saveRejectedCall();
            onReject.run();
            dismissAllowingStateLoss();
        });

        // Прозрачный фон
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        }

        return view;
    }

    @Override
    public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        super.onDismiss(dialog);
        if (!isActionTaken && onReject != null) {
            onReject.run();
        }
    }

    // [Добавлено]: Метод для сохранения отклонённого звонка
    private void saveRejectedCall() {
        if (hasSavedCall) {
            Log.d(TAG, "Call already saved, skipping saveRejectedCall");
            return;
        }

        // Получаем currentUserId из SharedPreferences
        SharedPreferences prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE);
        int currentUserId = prefs.getInt("userId", -1);
        if (currentUserId == -1) {
            Log.e(TAG, "Cannot save rejected call: currentUserId is not set");
            return;
        }

        OkHttpClient client = com.example.unlimitedpossibilities12.api.AuthToken.httpClient();
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        JSONObject callData = new JSONObject();
        try {
            Log.d(TAG, "saveRejectedCall called with callRequest: " + (callRequest != null ? callRequest.toString() : "null") +
                    ", groupCallRequest: " + (groupCallRequest != null ? groupCallRequest.toString() : "null"));
            if (groupCallRequest != null) {
                // Групповой звонок
                callData.put("caller", groupCallRequest.getInitiatorId()); // Используем "caller" для консистентности
                JSONArray participantsArray = new JSONArray();
                participantsArray.put(groupCallRequest.getInitiatorId());
                for (String recipientId : groupCallRequest.getRecipientIds()) {
                    participantsArray.put(recipientId);
                }
                callData.put("participants", participantsArray);
                callData.put("direction", "incoming");
                callData.put("status", "rejected");
                callData.put("callType", "group");
                callData.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date()));
                // Поле "roomId" исключено, как в GroupCallActivity
            } else if (callRequest != null) {
                // Индивидуальный звонок
                Log.d(TAG, "Call type: " + callRequest.getType());
                callData.put("caller", callRequest.getCallerId());
                callData.put("receiver", currentUserId);
                callData.put("direction", "incoming");
                callData.put("status", "rejected");
                callData.put("callType", callRequest.getType() != null ? callRequest.getType() : "voice");
                callData.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date()));
                callData.put("roomId", callRequest.getRoomId());
            } else {
                Log.e(TAG, "No valid call request provided for saving rejected call");
                return;
            }

            RequestBody body = RequestBody.create(callData.toString(), JSON);
            Request request = new Request.Builder()
                    .url("https://unlimitedpossibilities12.org/api/calls?userId=" + currentUserId)
                    .post(body)
                    .build();

            new Thread(() -> {
                try (Response response = client.newCall(request).execute()) {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "Rejected call saved to server with callType: " + callData.getString("callType") + ", JSON: " + callData.toString());
                        hasSavedCall = true;
                    } else {
                        Log.e(TAG, "Failed to save rejected call, response code: " + response.code() + ", response body: " +
                                (response.body() != null ? response.body().string() : "null"));
                    }
                } catch (IOException | JSONException e) {
                    Log.e(TAG, "Error saving rejected call: " + e.getMessage());
                }
            }).start();
        } catch (JSONException e) {
            Log.e(TAG, "Error creating rejected call JSON: " + e.getMessage());
        }
    }

    // Метод для генерации случайного цвета на основе имени
    private int getRandomColor(String name) {
        int[] colors = {
                Color.parseColor("#0288D1"), // Синий
                Color.parseColor("#D81B60"), // Розовый
                Color.parseColor("#4CAF50"), // Зеленый
                Color.parseColor("#FBC02D"), // Желтый
                Color.parseColor("#7B1FA2")  // Фиолетовый
        };
        int index = Math.abs(name.hashCode() % colors.length);
        return colors[index];
    }
}