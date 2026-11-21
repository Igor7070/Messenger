package com.example.unlimitedpossibilities12.messenger;

import android.content.Context;
import android.util.Log;

import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.PeerConnectionFactory;

/**
 * Завод соединений — один на всё время работы приложения.
 *
 * Прежде каждый экран звонка строил свой завод и рушил его, кладя
 * трубку. Внутри завода живёт звуковая часть со своим потоком, и этот
 * поток переживал снос: приложение падало при выходе из разговора
 * (SIGSEGV в AudioTrackJavaT) и перезапускалось. Отсрочка сноса
 * помогала не всегда — поток мог ещё работать и через полсекунды.
 *
 * Сносить завод незачем вовсе: он невелик, а между звонками просто
 * ждёт. Соединения, дорожки и источники по-прежнему освобождаются в
 * конце каждого разговора — вот их и нужно освобождать.
 */
public final class ЗаводСвязи {

    private static final String TAG = "ЗаводСвязи";

    private static EglBase общийEgl;
    private static PeerConnectionFactory завод;

    private ЗаводСвязи() { }

    /** Общий рисовальный контекст: он же отдан кодировщику и декодировщику. */
    public static synchronized EglBase egl() {
        if (общийEgl == null) {
            общийEgl = EglBase.create();
            Log.d(TAG, "Создан общий рисовальный контекст");
        }
        return общийEgl;
    }

    /** Завод соединений: создаётся при первом звонке и живёт дальше. */
    public static synchronized PeerConnectionFactory завод(Context контекст) {
        if (завод == null) {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                            .builder(контекст.getApplicationContext())
                            .createInitializationOptions());
            завод = PeerConnectionFactory.builder()
                    .setOptions(new PeerConnectionFactory.Options())
                    .setVideoEncoderFactory(
                            new DefaultVideoEncoderFactory(egl().getEglBaseContext(), true, true))
                    .setVideoDecoderFactory(
                            new DefaultVideoDecoderFactory(egl().getEglBaseContext()))
                    .createPeerConnectionFactory();
            Log.d(TAG, "Завод соединений создан — он один на всё приложение");
        }
        return завод;
    }
}
