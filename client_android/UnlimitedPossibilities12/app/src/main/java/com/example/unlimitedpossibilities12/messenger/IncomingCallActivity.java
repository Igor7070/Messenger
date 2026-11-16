package com.example.unlimitedpossibilities12.messenger;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

import com.example.unlimitedpossibilities12.api.CallRequest;
import com.example.unlimitedpossibilities12.api.GroupCallRequest;
import com.example.unlimitedpossibilities12.messenger.notife.CallActionReceiver;

/**
 * Входящий звонок на весь экран, когда приложение закрыто.
 *
 * Прежде разбуженное push-ом приложение только клало строку в шторку: телефон
 * звонил, но чтобы ответить, надо было раскрыть шторку и найти уведомление.
 * Теперь звонок, как в звонилке, сам выходит на экран и будит его.
 *
 * Сама карточка — то же окно, что и при открытом приложении, чтобы входящий
 * выглядел одинаково в обоих случаях. Ответ и отказ уходят тем же путём, что
 * и кнопки в уведомлении — через {@link CallActionReceiver}.
 */
public class IncomingCallActivity extends AppCompatActivity {

    private static final String TAG = "IncomingCallScreen";

    private CallRequest callRequest;
    private GroupCallRequest groupCallRequest;
    private int notificationId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOverLockScreen();

        callRequest = (CallRequest) getIntent().getSerializableExtra("callRequest");
        groupCallRequest = (GroupCallRequest) getIntent().getSerializableExtra("groupCallRequest");
        notificationId = getIntent().getIntExtra("notificationId", -1);

        if (callRequest == null && groupCallRequest == null) {
            Log.e(TAG, "Нет данных о звонке — закрываемся");
            finish();
            return;
        }

        Log.d(TAG, "Входящий: " + (callRequest != null
                ? callRequest.getCallerName() + ", снимок=" + callRequest.getCallerAvatar()
                : "групповой"));

        IncomingCallDialogFragment dialog = callRequest != null
                ? new IncomingCallDialogFragment(callRequest, this::accept, this::reject)
                : new IncomingCallDialogFragment(groupCallRequest, this::accept, this::reject);
        dialog.show(getSupportFragmentManager(), "incoming-call");
    }

    /** Экран звонка поднимается поверх блокировки и включает дисплей. */
    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void accept() {
        sendToReceiver("ACCEPT_CALL");
        finish();
    }

    private void reject() {
        sendToReceiver("REJECT_CALL");
        finish();
    }

    private void sendToReceiver(String action) {
        Intent intent = new Intent(this, CallActionReceiver.class);
        intent.setAction(action);
        intent.putExtra("notificationId", notificationId);
        if (callRequest != null) {
            intent.putExtra("callRequest", callRequest);
        } else {
            intent.putExtra("groupCallRequest", groupCallRequest);
        }
        sendBroadcast(intent);
        Log.d(TAG, "Отправлено действие " + action);
    }
}
