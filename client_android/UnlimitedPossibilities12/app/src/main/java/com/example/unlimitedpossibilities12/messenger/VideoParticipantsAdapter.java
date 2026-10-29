package com.example.unlimitedpossibilities12.messenger;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.User;

import org.webrtc.EglBase;
import org.webrtc.EglRenderer;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class VideoParticipantsAdapter extends RecyclerView.Adapter<VideoParticipantsAdapter.VideoViewHolder> {
    private static final String TAG = "VideoParticipantsAdapter";
    private List<User> participants;
    private String currentUserId;
    private EglBase eglBase;
    private OnCameraToggleListener cameraToggleListener;
    private Map<String, Boolean> cameraStates;
    private Map<String, SurfaceViewRenderer> videoViews;
    private Map<String, Boolean> videoViewInitialized;
    private Map<String, Boolean> videoTracksBound;
    private Map<String, VideoTrack> boundVideoTracks;

    /** Форма плиток: по потоку каждого участника. */
    public static final String MODE_AUTO = "auto";
    /** Форма плиток: у всех принудительно горизонтальная. */
    public static final String MODE_LANDSCAPE = "landscape";
    /** Форма плиток: у всех принудительно вертикальная. */
    public static final String MODE_PORTRAIT = "portrait";

    private static final float ASPECT_LANDSCAPE = 16f / 9f;
    private static final float ASPECT_PORTRAIT = 9f / 16f;

    private String tileMode = MODE_AUTO;
    /** userId -> соотношение сторон входящего видео (ширина / высота). */
    private final Map<String, Float> videoAspects = new HashMap<>();

    public interface OnCameraToggleListener {
        void onCameraToggle(boolean enable);
    }

    public VideoParticipantsAdapter(List<User> participants, String currentUserId, EglBase remoteEglBase, OnCameraToggleListener cameraToggleListener) {
        this.participants = participants;
        this.currentUserId = currentUserId;
        this.eglBase = remoteEglBase;
        this.cameraToggleListener = cameraToggleListener;
        this.cameraStates = new HashMap<>();
        this.videoViews = new HashMap<>();
        this.videoViewInitialized = new HashMap<>();
        this.videoTracksBound = new HashMap<>();
        this.boundVideoTracks = new HashMap<>();
        for (User user : participants) {
            String userId = String.valueOf(user.getId());
            cameraStates.put(userId, false);
            videoViewInitialized.put(userId, false);
            videoTracksBound.put(userId, false);
        }
        setHasStableIds(true);
        Log.d(TAG, "Adapter initialized with currentUserId=" + currentUserId + ", participants size=" + participants.size());

        for (User user : participants) {
            String userId = String.valueOf(user.getId());
            cameraStates.put(userId, false);
            videoViewInitialized.put(userId, false);
            videoTracksBound.put(userId, false);
            boundVideoTracks.put(userId, null); // Явно очищаем трек
        }
        Log.d(TAG, "Adapter initialized with ...");
    }

    @Override
    public long getItemId(int position) {
        return participants.get(position).getId();
    }

    public boolean isVideoViewInitialized(String userId) {
        return videoViewInitialized.getOrDefault(userId, false);
    }

    public void setVideoViewInitialized(String userId, boolean initialized) {
        videoViewInitialized.put(userId, initialized);
        Log.d(TAG, "setVideoViewInitialized: userId=" + userId + ", initialized=" + initialized);
    }

    public boolean isVideoTrackBound(String userId) {
        return videoTracksBound.getOrDefault(userId, false);
    }

    public void setVideoTrackBound(String userId, boolean bound) {
        videoTracksBound.put(userId, bound);
        Log.d(TAG, "setVideoTrackBound: userId=" + userId + ", bound=" + bound);
    }

    public SurfaceViewRenderer getVideoView(String userId) {
        SurfaceViewRenderer videoView = videoViews.get(userId);
        Log.d(TAG, "getVideoView: userId=" + userId + ", videoView=" + (videoView != null ? videoView.toString() : "null"));
        return videoView;
    }

    public void setVideoTrack(String userId, VideoTrack videoTrack) {
        // [ДОБАВЛЕНО]: Игнорируем локального пользователя
        if (userId.equals(currentUserId)) {
            Log.d(TAG, "setVideoTrack: Ignoring for currentUserId=" + userId + " (handled by localVideoView)");
            return;
        }
        Log.d(TAG, "setVideoTrack: userId=" + userId + ", videoTrack=" + (videoTrack != null ? "not null" : "null"));

        // [КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ]: Сохраняем трек и сбрасываем флаг привязки
        // Пояснение: активность уже могла привязать sink вручную — мы перезапишем и привяжем правильно
        boundVideoTracks.put(userId, videoTrack);
        setVideoTrackBound(userId, false);  // Принудительно сбрасываем — будем привязывать заново
        setCameraState(userId, videoTrack != null);

        SurfaceViewRenderer videoView = videoViews.get(userId);
        if (videoView == null) {
            Log.w(TAG, "VideoView is null for userId: " + userId + " — track saved, will bind in onBindViewHolder");
            int position = getParticipantPosition(userId);
            if (position != -1) {
                notifyItemChanged(position);
            }
            return;
        }

        runOnUiThread(() -> {
            videoView.setVisibility(View.VISIBLE);
            videoView.clearImage();  // [НОВОЕ]: очищаем старое изображение на всякий случай
            videoView.requestLayout();

            Log.d(TAG, "setVideoTrack: forcing re-attachment for userId=" + userId + ", width=" + videoView.getWidth() + ", height=" + videoView.getHeight());

            // Принудительно привязываем с увеличенным числом попыток
            attachSinkWithRetry(videoView, userId, videoTrack, 0, 6);  // Увеличили до 6 попыток

            int position = getParticipantPosition(userId);
            if (position != -1) {
                notifyItemChanged(position);
            }
        });
    }

    public void setCameraState(String userId, boolean isCameraOn) {
        // [ДОБАВЛЕНО]: Игнорируем локального пользователя
        if (userId.equals(currentUserId)) {
            Log.d(TAG, "setCameraState: Ignoring for currentUserId=" + userId + " (handled by localVideoView)");
            return;
        }
        Log.d(TAG, "setCameraState: userId=" + userId + ", isCameraOn=" + isCameraOn);
        cameraStates.put(userId, isCameraOn);
        runOnUiThread(() -> {
            int position = getParticipantPosition(userId);
            if (position != -1) {
                notifyItemChanged(position);
            }
        });
    }

    private int getParticipantPosition(String userId) {
        // [ИЗМЕНЕНО]: Учитываем только удалённых участников
        int position = 0;
        for (User user : participants) {
            if (!String.valueOf(user.getId()).equals(currentUserId)) {
                if (String.valueOf(user.getId()).equals(userId)) {
                    return position;
                }
                position++;
            }
        }
        Log.w(TAG, "getParticipantPosition: userId=" + userId + " not found in filtered participants");
        return -1;
    }

    private void runOnUiThread(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    public void updateParticipants(List<User> newParticipants) {
        Log.d(TAG, "updateParticipants: old size=" + participants.size() + ", new size=" + newParticipants.size());
        Map<String, Boolean> newCameraStates = new HashMap<>();
        Map<String, Boolean> newVideoViewInitialized = new HashMap<>();
        Map<String, Boolean> newVideoTracksBound = new HashMap<>();
        Map<String, VideoTrack> newBoundVideoTracks = new HashMap<>();
        for (User user : newParticipants) {
            String userId = String.valueOf(user.getId());
            // [ДОБАВЛЕНО]: Пропускаем локального пользователя
            if (!userId.equals(currentUserId)) {
                newCameraStates.put(userId, cameraStates.getOrDefault(userId, false));
                newVideoViewInitialized.put(userId, videoViewInitialized.getOrDefault(userId, false));
                newVideoTracksBound.put(userId, videoTracksBound.getOrDefault(userId, false));
                newBoundVideoTracks.put(userId, boundVideoTracks.getOrDefault(userId, null));
            }
        }
        cameraStates = newCameraStates;
        videoViewInitialized = newVideoViewInitialized;
        videoTracksBound = newVideoTracksBound;
        boundVideoTracks = newBoundVideoTracks;
        this.participants = newParticipants;
        notifyDataSetChanged();
    }

    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_video_participant, parent, false);
        VideoViewHolder holder = new VideoViewHolder(view);
        try {
            holder.videoView.init(eglBase.getEglBaseContext(), new RendererCommon.RendererEvents() {
                @Override
                public void onFirstFrameRendered() {
                    Log.d(TAG, "First frame rendered for holder position: " + holder.getAdapterPosition());
                    String userId = getUserIdFromPosition(holder.getAdapterPosition());
                    if (userId != null && isVideoTrackBound(userId)) {
                        holder.videoView.invalidate();
                    }
                }
                @Override
                public void onFrameResolutionChanged(int videoWidth, int videoHeight, int rotation) {
                    Log.d(TAG, "Frame resolution changed: " + videoWidth + "x" + videoHeight
                            + ", rotation " + rotation + " for position: " + holder.getAdapterPosition());

                    // Запоминаем реальные пропорции отправителя — по ним режим
                    // auto подбирает форму плитки. При повороте на 90/270
                    // ширина и высота меняются местами.
                    int width = videoWidth;
                    int height = videoHeight;
                    if (rotation == 90 || rotation == 270) {
                        int tmp = width;
                        width = height;
                        height = tmp;
                    }
                    final String userId = getUserIdFromPosition(holder.getAdapterPosition());
                    if (userId != null && width > 0 && height > 0) {
                        videoAspects.put(userId, (float) width / (float) height);
                    }

                    // Переносим requestLayout в UI-поток
                    holder.videoView.post(() -> {
                        if (userId != null) applyTileShape(holder, userId);
                        holder.videoView.requestLayout();
                        holder.videoView.invalidate();
                        Log.d(TAG, "Frame resolution changed: triggered layout in UI thread for position: " + holder.getAdapterPosition());
                    });
                }
            });
            holder.videoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
            // setEnableHardwareScaler(true) намеренно НЕ включаем: он ужимает буфер
            // поверхности до размера кадра и растягивает его компоновщиком, из-за
            // чего вписывание в плитку получается неточным. У локального вида он
            // выключен по той же причине.
            holder.videoView.setZOrderMediaOverlay(true);
            holder.isInitialized = true;
            Log.d(TAG, "Initialized SurfaceViewRenderer in onCreateViewHolder");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing SurfaceViewRenderer: " + e.getMessage(), e);
        }
        return holder;
    }

    /** Текущий режим формы плиток. */
    public String getTileMode() {
        return tileMode;
    }

    /**
     * Переключает форму плиток участников.
     * MODE_AUTO подбирает форму по реальному разрешению потока каждого,
     * MODE_LANDSCAPE и MODE_PORTRAIT делают все плитки одинаковыми.
     */
    public void setTileMode(String mode) {
        if (mode == null) mode = MODE_AUTO;
        if (mode.equals(tileMode)) return;
        tileMode = mode;
        Log.d(TAG, "Режим формы плиток: " + tileMode);
        notifyDataSetChanged();
    }

    /** Пропорции плитки участника (ширина / высота) с учётом выбранного режима. */
    private float getTileAspect(String userId) {
        if (MODE_LANDSCAPE.equals(tileMode)) return ASPECT_LANDSCAPE;
        if (MODE_PORTRAIT.equals(tileMode)) return ASPECT_PORTRAIT;
        Float aspect = videoAspects.get(userId);
        // Пока разрешение потока неизвестно, показываем привычную горизонтальную плитку.
        return (aspect != null && aspect > 0f) ? aspect : ASPECT_LANDSCAPE;
    }

    /**
     * Подгоняет высоту контейнера видео под нужные пропорции.
     * В разметке высота задана лишь как значение по умолчанию — реальную
     * ставим здесь, когда известна ширина ячейки.
     */
    private void applyTileShape(VideoViewHolder holder, String userId) {
        final View container = holder.videoContainer;
        if (container == null) return;

        final Runnable apply = () -> {
            int width = container.getWidth();
            if (width <= 0) return;

            int height = Math.round(width / getTileAspect(userId));
            // Вертикальная плитка на всю ширину экрана вышла бы выше экрана —
            // ограничиваем половиной высоты дисплея.
            int maxHeight = Math.round(container.getResources().getDisplayMetrics().heightPixels * 0.5f);
            height = Math.max(1, Math.min(height, maxHeight));

            ViewGroup.LayoutParams params = container.getLayoutParams();
            if (params != null && params.height != height) {
                params.height = height;
                container.setLayoutParams(params);
            }
        };

        if (container.getWidth() > 0) {
            apply.run();
        } else {
            container.post(apply); // ширина ячейки ещё не измерена
        }
    }

    /**
     * Прячет надпись «No Video» в ЭТОЙ карточке участника.
     * Раньше здесь было videoView.getRootView().findViewById(...), а getRootView()
     * возвращает корень всей иерархии активности, поэтому findViewById находил
     * первый попавшийся no_video_text в списке — то есть надпись чужого участника.
     */
    private void hideNoVideoLabelFor(SurfaceViewRenderer videoView) {
        View itemRoot = (View) videoView.getParent();
        if (itemRoot == null) return;
        TextView noVideoText = itemRoot.findViewById(R.id.no_video_text);
        if (noVideoText != null) noVideoText.setVisibility(View.GONE);
    }

    private void attachSinkWithRetry(SurfaceViewRenderer videoView, String userId, VideoTrack videoTrack, int attempt, int maxAttempts) {
        videoView.post(() -> {
            int width = videoView.getWidth();
            int height = videoView.getHeight();
            Log.d(TAG, "attachSinkWithRetry attempt=" + attempt + ", width=" + width + ", height=" + height + " for userId=" + userId);
            if (width > 0 && height > 0) {
                if (videoTrack != null && !isVideoTrackBound(userId)) {
                    VideoTrack oldTrack = boundVideoTracks.get(userId);
                    if (oldTrack != null) {
                        oldTrack.removeSink(videoView);
                    }
                    videoTrack.addSink(videoView);
                    boundVideoTracks.put(userId, videoTrack);
                    setVideoTrackBound(userId, true);
                    Log.d(TAG, "Attached video track after layout ready for userId: " + userId);
                    videoView.invalidate();
                    hideNoVideoLabelFor(videoView);
                    int position = getParticipantPosition(userId);
                    if (position != -1) notifyItemChanged(position);
                } else if (videoTrack != null) {
                    Log.d(TAG, "Video track already bound or null for userId: " + userId);
                }
            } else if (attempt < maxAttempts) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> attachSinkWithRetry(videoView, userId, videoTrack, attempt + 1, maxAttempts), 100);
            } else {
                Log.e(TAG, "Failed to attach sink after " + maxAttempts + " attempts — sizes still 0 for userId: " + userId);
                // Fallback: attach anyway
                if (videoTrack != null && !isVideoTrackBound(userId)) {
                    videoTrack.addSink(videoView);
                    boundVideoTracks.put(userId, videoTrack);
                    setVideoTrackBound(userId, true);
                    Log.d(TAG, "Fallback: Attached video track despite zero sizes for userId: " + userId);
                    videoView.invalidate();
                    hideNoVideoLabelFor(videoView);
                    int position = getParticipantPosition(userId);
                    if (position != -1) notifyItemChanged(position);
                }
            }
        });
    }

    // [ИЗМЕНЕНО]: Учитываем исключение локального участника
    private String getUserIdFromPosition(int position) {
        User user = getFilteredParticipant(position);
        if (user != null) {
            return String.valueOf(user.getId());
        }
        Log.w(TAG, "getUserIdFromPosition: No user found for position=" + position);
        return null;
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        // [ДОБАВЛЕНО]: Получаем отфильтрованный список участников, исключая текущего пользователя
        User user = getFilteredParticipant(position);
        String userId = String.valueOf(user.getId());
        Log.d(TAG, "onBindViewHolder: position=" + position + ", userId=" + userId + ", width=" + holder.videoView.getWidth() + ", height=" + holder.videoView.getHeight());

        holder.usernameTextView.setText(user.getUsername());
        videoViews.put(userId, holder.videoView);
        applyTileShape(holder, userId);

        boolean isCameraOn = cameraStates.getOrDefault(userId, false);

        // [НОВОЕ]: Проверяем, есть ли уже сохранённый трек (может прийти раньше, чем bind)
        // Пояснение: без этого видео не появляется, если трек пришёл до создания ячейки
        VideoTrack savedTrack = boundVideoTracks.get(userId);

        if (savedTrack != null) {
            // Есть трек — показываем видео
            holder.videoView.setVisibility(View.VISIBLE);
            holder.noVideoText.setVisibility(View.GONE);

            // [НОВОЕ]: Если трек ещё не привязан к этому view — привязываем
            // Пояснение: view мог быть пересоздан (scroll, recycle), старый sink потерян
            if (!isVideoTrackBound(userId)) {
                attachSinkWithRetry(holder.videoView, userId, savedTrack, 0, 3);
            }
        } else {
            // Нет трека — показываем placeholder
            holder.videoView.setVisibility(View.GONE);
            holder.noVideoText.setVisibility(View.VISIBLE);
        }

        // Форсируем layout
        holder.videoView.requestLayout();
        holder.videoView.postInvalidate();

        // [ИЗМЕНЕНО]: Кнопка toggleCameraButton видна только для удалённых участников
        holder.toggleCameraButton.setVisibility(View.GONE);
    }

    // [ДОБАВЛЕНО]: Возвращает участника, пропуская текущего пользователя
    private User getFilteredParticipant(int position) {
        int currentPosition = 0;
        for (User user : participants) {
            if (!String.valueOf(user.getId()).equals(currentUserId)) {
                if (currentPosition == position) {
                    return user;
                }
                currentPosition++;
            }
        }
        Log.e(TAG, "getFilteredParticipant: No participant found for position=" + position);
        return participants.get(0); // Fallback, если позиция некорректна
    }

    @Override
    public void onViewRecycled(@NonNull VideoViewHolder holder) {
        super.onViewRecycled(holder);
        int position = holder.getAdapterPosition();
        if (position >= 0 && position < participants.size()) {
            String userId = String.valueOf(participants.get(position).getId());
            SurfaceViewRenderer videoView = videoViews.get(userId);
            if (videoView != null) {
                VideoTrack track = boundVideoTracks.get(userId);
                if (track != null) {
                    track.removeSink(videoView);
                    Log.d(TAG, "Removed video track sink for userId: " + userId);
                    boundVideoTracks.remove(userId);
                }
                videoView.clearImage();
                try {
                    videoView.release();
                    Log.d(TAG, "Released SurfaceViewRenderer for userId: " + userId);
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing SurfaceViewRenderer for userId: " + userId + ", error: " + e.getMessage());
                }
                videoViews.remove(userId);
                setVideoViewInitialized(userId, false);
                setVideoTrackBound(userId, false);
                holder.isInitialized = false;
            }
        } else {
            Log.w(TAG, "Invalid position in onViewRecycled: " + position);
        }
    }

    @Override
    public int getItemCount() {
        // [ИЗМЕНЕНО]: Уменьшаем счётчик, исключая локального участника
        int count = participants.size();
        for (User user : participants) {
            if (String.valueOf(user.getId()).equals(currentUserId)) {
                count--;
                break;
            }
        }
        Log.d(TAG, "getItemCount: total=" + participants.size() + ", excluding currentUserId=" + currentUserId + ", returning=" + count);
        return count;
    }

    public Map<String, SurfaceViewRenderer> getVideoViews() {
        return videoViews;
    }

    static class VideoViewHolder extends RecyclerView.ViewHolder {
        TextView usernameTextView;
        SurfaceViewRenderer videoView;
        TextView noVideoText;
        Button toggleCameraButton;
        View videoContainer;
        boolean isInitialized;

        VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            usernameTextView = itemView.findViewById(R.id.username_text_view);
            videoView = itemView.findViewById(R.id.video_view);
            videoContainer = itemView.findViewById(R.id.video_container);
            noVideoText = itemView.findViewById(R.id.no_video_text);
            toggleCameraButton = itemView.findViewById(R.id.toggle_camera_button);
            isInitialized = false;
            Log.d(TAG, "Created VideoViewHolder for videoView=" + (videoView != null ? videoView.toString() : "null"));
        }
    }
}