package com.example.unlimitedpossibilities12.messenger.notife;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.example.unlimitedpossibilities12.api.App;
import com.example.unlimitedpossibilities12.api.CallRequest;
import com.example.unlimitedpossibilities12.api.GroupCallRequest;
import com.example.unlimitedpossibilities12.messenger.GroupCallActivity;
import com.example.unlimitedpossibilities12.messenger.SecureVoiceCallActivity;
import com.example.unlimitedpossibilities12.messenger.VideoCallActivity;
import com.example.unlimitedpossibilities12.messenger.VoiceCallActivity;
import com.example.unlimitedpossibilities12.messenger.VoiceCallWithTranslateActivity;

import java.util.ArrayList;

public class CallActionReceiver extends BroadcastReceiver {
    private static final String TAG = "CallActionReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        CallRequest callRequest = (CallRequest) intent.getSerializableExtra("callRequest");
        GroupCallRequest groupCallRequest = (GroupCallRequest) intent.getSerializableExtra("groupCallRequest");

        if (callRequest == null && groupCallRequest == null) {
            Log.e(TAG, "Both CallRequest and GroupCallRequest are null");
            return;
        }

        // Получаем notificationId из Intent
        int notificationId = intent.getIntExtra("notificationId", -1);
        Intent serviceIntent = new Intent(context, CallService.class);
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        int currentUserId = ((App) context.getApplicationContext()).getCurrentUserId();

        if ("ACCEPT_CALL".equals(intent.getAction())) {
            Intent callIntent;
            String roomId;
            String callerName;

            if (callRequest != null) {
                // Обработка звонка 1:1
                callerName = callRequest.getCallerName();
                roomId = callRequest.getRoomId();
                Log.d(TAG, "Accepting 1:1 call from " + callerName + ", type: " + callRequest.getType());

                // Запускаем соответствующую активность
                switch (callRequest.getType()) {
                    case "video":
                        callIntent = new Intent(context, VideoCallActivity.class);
                        break;
                    case "translate":
                        callIntent = new Intent(context, VoiceCallWithTranslateActivity.class);
                        break;
                    case "voice_secure":
                        callIntent = new Intent(context, SecureVoiceCallActivity.class);
                        break;
                    case "voice":
                    default:
                        callIntent = new Intent(context, VoiceCallActivity.class);
                        break;
                }
                callIntent.putExtra("currentUserId", String.valueOf(currentUserId));
                callIntent.putExtra("recipientId", String.valueOf(callRequest.getCallerId()));
                callIntent.putExtra("roomId", callRequest.getRoomId());
                callIntent.putExtra("isCaller", false);
                callIntent.putExtra("callerName", callerName);
                callIntent.putExtra("recipientName", callerName);
            } else {
                // Обработка группового звонка
                callerName = groupCallRequest.getInitiatorName();
                roomId = groupCallRequest.getRoomId();
                Log.d(TAG, "Accepting group call from " + callerName);

                callIntent = new Intent(context, GroupCallActivity.class);
                callIntent.putExtra("initiatorId", Integer.parseInt(groupCallRequest.getInitiatorId()));
                callIntent.putExtra("initiatorName", groupCallRequest.getInitiatorName());
                callIntent.putStringArrayListExtra("selectedUsersIds", new ArrayList<>(groupCallRequest.getRecipientIds()));
                callIntent.putExtra("roomId", groupCallRequest.getRoomId());
                callIntent.putExtra("isInitiator", false);
            }

            // Останавливаем звук и отправляем сигнал acceptCall
            serviceIntent.setAction("STOP_CALL_SOUND");
            serviceIntent.putExtra("action", "ACCEPT_CALL");
            serviceIntent.putExtra("roomId", roomId);
            context.startService(serviceIntent);

            // Запускаем активность
            callIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(callIntent);

            // Отменяем уведомление
            if (notificationId != -1) {
                notificationManager.cancel(notificationId);
                Log.d(TAG, "Notification canceled with ID: " + notificationId);
            }
        } else if ("REJECT_CALL".equals(intent.getAction())) {
            String callerName;
            String roomId;

            if (callRequest != null) {
                callerName = callRequest.getCallerName();
                roomId = callRequest.getRoomId();
                Log.d(TAG, "Rejecting 1:1 call from " + callerName + ", type: " + callRequest.getType());
                serviceIntent.putExtra("callRequest", callRequest); // Добавляем callRequest
            } else {
                callerName = groupCallRequest.getInitiatorName();
                roomId = groupCallRequest.getRoomId();
                Log.d(TAG, "Rejecting group call from " + callerName);
                serviceIntent.putExtra("groupCallRequest", groupCallRequest); // Добавляем groupCallRequest
            }

            // Останавливаем звук и отправляем сигнал rejectCall
            serviceIntent.setAction("STOP_CALL_SOUND");
            serviceIntent.putExtra("action", "REJECT_CALL");
            serviceIntent.putExtra("roomId", roomId);
            context.startService(serviceIntent);

            // Отменяем уведомление
            if (notificationId != -1) {
                notificationManager.cancel(notificationId);
                Log.d(TAG, "Notification canceled with ID: " + notificationId);
            }
        }
    }
}