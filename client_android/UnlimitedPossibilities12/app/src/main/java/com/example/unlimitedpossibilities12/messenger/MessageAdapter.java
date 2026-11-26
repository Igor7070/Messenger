package com.example.unlimitedpossibilities12.messenger;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ListPopupWindow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.example.unlimitedpossibilities12.R;
import com.example.unlimitedpossibilities12.api.ApiClient;
import com.example.unlimitedpossibilities12.api.ApiService;
import com.example.unlimitedpossibilities12.api.Message;
import com.example.unlimitedpossibilities12.api.User;
import com.example.unlimitedpossibilities12.messenger.popupMenu.PopupMenuAdapter;
import com.example.unlimitedpossibilities12.messenger.popupMenu.PopupMenuItem;
import com.example.unlimitedpossibilities12.messenger.translate_and_spell_check.PrivateChatTranslationAndSpellCheckHelper;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

    /**
     * Кто ведает выбором нескольких сообщений.
     *
     * Долгое нажатие начинает выбор, дальше обычные нажатия его
     * пополняют. Пока выбор идёт, нажатие не переключает перевод и не
     * открывает меню.
     */

    /**
     * Чья это строка: номер отправителя, а для карточки звонка — своя
     * сторона (исходящий или входящий).
     */
    private long чьяСтрока(Message строка) {
        if (строка == null) return 0;
        if (строка.getMessageType() != null && строка.getMessageType().startsWith("call")) {
            return "call_out".equals(строка.getMessageType()) ? -1 : -2;
        }
        return строка.getSender() == null ? 0 : строка.getSender().getId();
    }

    /**
     * Насколько отодвинуть строку от предыдущей.
     *
     * Подряд идущие от одного — почти вплотную, при смене отправителя
     * просвет больше: так в телеграме видно, где сменилась реплика.
     */

    /** Отодвинуть сообщение от плашки с датой — зовём, когда дата уже выставлена. */
    private void отступПодДатой(View строка) {
        if (строка == null) return;
        float плотность = строка.getResources().getDisplayMetrics().density;
        /* Под плашкой с датой отступ задаём самому сообщению: нижнее поле
           плашки тут никто не слушает — в этой разметке важно поле того,
           кто к ней привязан. Сверху и снизу от даты выходит поровну. */
        View плашка = строка.findViewById(R.id.day_separator);
        View пузырь = строка.findViewById(R.id.message_container);
        if (пузырь != null && пузырь.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            int подДатой = (плашка != null && плашка.getVisibility() == View.VISIBLE)
                    ? Math.round(10 * плотность) : 0;
            ViewGroup.MarginLayoutParams мера = (ViewGroup.MarginLayoutParams) пузырь.getLayoutParams();
            if (мера.topMargin != подДатой) {
                мера.topMargin = подДатой;
                пузырь.setLayoutParams(мера);
            }
        }
    }


    /* ───── Время строки считаем один раз ───── */

    /** Киевская зона и образцы времени: искать и собирать их заново на
        каждую строку дорого — при прокрутке это заметный рывок. */
    private static final java.time.ZoneId КИЕВ = java.time.ZoneId.of("Europe/Kiev");
    private static final DateTimeFormatter ЧАСЫ =
            DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault());
    private static final DateTimeFormatter ДЕНЬ_ЭТОГО_ГОДА =
            DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter ДЕНЬ_С_ГОДОМ =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    /** Разобранные метки времени: одна и та же строка разбирается раз. */
    private final java.util.HashMap<String, ZonedDateTime> разобранное = new java.util.HashMap<>();

    /** Загрузчик картинок: искать его заново на каждую строку дорого. */
    private com.bumptech.glide.RequestManager стекло;

    private com.bumptech.glide.RequestManager стекло(Context где) {
        if (стекло == null) стекло = Glide.with(где.getApplicationContext());
        return стекло;
    }

    /** Метка времени строки — из запаса, а если её там нет, разбираем. */
    private ZonedDateTime поКиеву(String метка) {
        if (метка == null || метка.isEmpty()) return null;
        ZonedDateTime готовое = разобранное.get(метка);
        if (готовое != null) return готовое;
        try {
            ZonedDateTime своё = ZonedDateTime.parse(метка, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                    .withZoneSameInstant(КИЕВ);
            if (разобранное.size() > 2000) разобранное.clear();
            разобранное.put(метка, своё);
            return своё;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void развестиСтроки(View строка, int место) {
        if (строка == null || место < 0 || место >= messages.size()) return;
        boolean тотЖеСверху = место > 0
                && чьяСтрока(messages.get(место)) == чьяСтрока(messages.get(место - 1));
        boolean тотЖеСнизу = место + 1 < messages.size()
                && чьяСтрока(messages.get(место)) == чьяСтрока(messages.get(место + 1));
        float плотность = строка.getResources().getDisplayMetrics().density;
        /* Просвет делим поровну между соседями: половина сверху, половина
           снизу. Между репликами одного выходит те же 2dp, между разными
           10dp, зато полоса выделения каждого доходит до середины просвета
           и у подряд выбранных кромки сходятся вплотную. */
        int сверху = Math.round((тотЖеСверху ? 1 : 5) * плотность);
        int снизу = Math.round((тотЖеСнизу ? 1 : 5) * плотность);
        if (строка.getPaddingTop() != сверху || строка.getPaddingBottom() != снизу) {
            строка.setPadding(строка.getPaddingLeft(), сверху, строка.getPaddingRight(), снизу);
        }
    }


    /**
     * Полоса выделения вокруг строки.
     *
     * Она обнимает пузырь с небольшим просветом сверху и снизу — как в
     * телеграме, — а не заливает весь промежуток между репликами. Просвет
     * один и тот же, своё это сообщение или чужое.
     */
    private void полосаВыделения(View строка, boolean выбрано) {
        if (строка == null) return;
        if (!выбрано) {
            строка.setBackground(null);
            return;
        }
        покраситьПолосу(строка);
        /* Перед самой отрисовкой пересчитываем: на привязке высота плашки с
           датой ещё нулевая, а к этому мигу она уже настоящая. Трогаем
           только фон, поэтому лента никуда не едет. */
        строка.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                строка.getViewTreeObserver().removeOnPreDrawListener(this);
                if (строка.getBackground() != null) покраситьПолосу(строка);
                return true;
            }
        });
    }

    /** Закрасить строку, оставив дату над сообщением нетронутой. */
    private void покраситьПолосу(View строка) {
        int сверху = 0;
        int снизу = 0;
        View дата = строка.findViewById(R.id.day_separator);
        if (дата != null && дата.getVisibility() == View.VISIBLE) {
            int высота = дата.getHeight();
            if (высота <= 0) {
                дата.measure(View.MeasureSpec.makeMeasureSpec(
                                Math.max(строка.getWidth(), строка.getResources()
                                        .getDisplayMetrics().widthPixels), View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                высота = дата.getMeasuredHeight();
            }
            /* Полоса начинается на середине просвета под датой: за верх
               сообщения она заходит ровно настолько же, насколько за низ. */
            сверху = строка.getPaddingTop() + высота
                    + Math.round(5 * строка.getResources().getDisplayMetrics().density);
        }
        /* Отступы у такого фона Android считает отступами строки и
           переписывает ими наши — потому здесь он о них молчит. */
        строка.setBackground(new android.graphics.drawable.InsetDrawable(
                new android.graphics.drawable.ColorDrawable(0xFF1D2D4A), 0, сверху, 0, снизу) {
            @Override
            public boolean getPadding(android.graphics.Rect отступы) {
                отступы.set(0, 0, 0, 0);
                return false;
            }
        });
    }


    /**
     * Перерисовать строку с этим номером.
     *
     * Ищем по своему списку: в нём, кроме сообщений, есть и карточки
     * звонков — в чате их тоже выбирают, а в списке переписки у хозяина
     * экрана их может не быть.
     */
    public void обновитьСтроку(long номер) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getId() == номер) {
                notifyItemChanged(i);
                return;
            }
        }
    }


    /* ───── Кружок выбора, как в телеграме ───── */

    /** Поставить кружок слева от строки: пустой или с галочкой. */
    private void кружокВыбора(View строка, Message сообщение) {
        if (строка == null) return;
        android.widget.ImageView кружок = строка.findViewById(R.id.select_mark);
        if (кружок == null) return;
        boolean идёт = выбор != null && выбор.идёт();
        кружок.setVisibility(идёт ? View.VISIBLE : View.GONE);
        if (идёт) {
            кружок.setImageResource(выбор.выбрано(сообщение)
                    ? R.drawable.ic_select_circle_checked : R.drawable.ic_select_circle);
        }
    }

    /**
     * Сколько места освободить слева под кружок.
     *
     * Двигаются только полученные: свои прижаты к правому краю, и кружку
     * слева они не мешают — так же сделано в телеграме.
     */
    private int местоПодКружок(float плотность) {
        return (выбор != null && выбор.идёт()) ? Math.round(40 * плотность) : 0;
    }

    /** Отодвинуть вьюху от левого края на столько, сколько просит кружок. */
    private void подвинутьПодКружок(View что, int обычный) {
        if (что == null || !(что.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        float плотность = что.getResources().getDisplayMetrics().density;
        int нужно = обычный + местоПодКружок(плотность);
        ViewGroup.MarginLayoutParams мера = (ViewGroup.MarginLayoutParams) что.getLayoutParams();
        if (мера.getMarginStart() != нужно) {
            мера.setMarginStart(нужно);
            что.setLayoutParams(мера);
        }
    }

    public interface ВыборСообщений {
        void начать(Message сообщение);
        void переключить(Message сообщение);
        boolean идёт();
        boolean выбрано(Message сообщение);
    }

    private ВыборСообщений выбор;

    public void setВыбор(ВыборСообщений выбор) {
        this.выбор = выбор;
    }

    /** Кому сказать, что карточку звонка просят убрать. */
    public interface СлушательЗвонков {
        void убрать(Message карточка);
        /** Трубка на карточке: звоним снова тем же видом связи. */
        void позвонить(Message карточка);
    }

    private СлушательЗвонков слушательЗвонков;

    public void setСлушательЗвонков(СлушательЗвонков слушатель) {
        this.слушательЗвонков = слушатель;
    }

    private static final String TAG = "MessageAdapter";
    private final Context context;
    private final List<Message> messages;
    private final int currentUserId;
    private final OnMessageActionListener actionListener;
    private final Map<Long, Boolean> userStatuses = new HashMap<>(); // Статусы пользователей (онлайн/оффлайн)
    private boolean isTranslationEnabled;
    private MediaPlayer mediaPlayer; // Добавлено: MediaPlayer для воспроизведения аудио
    private boolean isTTSEnabled; // ДОБАВЛЕНО: Переменная для TTS
    private MediaPlayer currentMediaPlayer;
    private long currentPlayingMessageId = -1;
    private boolean isPlayingTranslated = false;
    private boolean isTranscriptionEnabled; // Переменная для состояния транскрипции

    public interface OnMessageActionListener {
        void onEdit(Message message);
        void onDelete(Message message);
    }

    /** Чьи сообщения адаптер считает своими. */
    public int getCurrentUserId() {
        return currentUserId;
    }

    public MessageAdapter(Context context, List<Message> messages, int currentUserId, OnMessageActionListener listener, boolean isTranslationEnabled, boolean isTranscriptionEnabled) {
        this.context = context;
        this.messages = messages != null ? new ArrayList<>(messages) : new ArrayList<>();
        this.currentUserId = currentUserId;
        this.actionListener = listener;
        this.isTranslationEnabled = isTranslationEnabled;
        this.isTTSEnabled = false;
        this.isTranscriptionEnabled = isTranscriptionEnabled;
    }

    /** Вид ячейки «звонок»: такие рисуем карточкой, как в телеграме. */
    public static final int ТИП_ЗВОНОК = 77;


    /**
     * Чьё сообщение стоит выше этой строки.
     *
     * Карточки звонков перешагиваем: у них нет отправителя, а по нему
     * решают, скруглять ли угол пузыря и ставить ли аватарку.
     * Возвращает -1, если выше сообщений нет.
     */
    /** Кто написал следующую строку: по ней видно, последняя ли эта в череде. */
    private long ктоНиже(int место) {
        for (int i = место + 1; i < messages.size(); i += 1) {
            Message строка = messages.get(i);
            if (строка != null && строка.getSender() != null) return строка.getSender().getId();
        }
        return -1;
    }

    private long ктоВыше(int место) {
        for (int i = место - 1; i >= 0; i -= 1) {
            Message строка = messages.get(i);
            if (строка != null && строка.getSender() != null) return строка.getSender().getId();
        }
        return -1;
    }

    @Override
    public int getItemViewType(int position) {
        Message m = messages.get(position);
        return (m != null && m.getMessageType() != null && m.getMessageType().startsWith("call"))
                ? ТИП_ЗВОНОК : 0;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(
                viewType == ТИП_ЗВОНОК ? R.layout.item_call_card : R.layout.item_message,
                parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        Message message = messages.get(position);
        if (holder.callCardText != null && message != null) {
            /* Карточка звонка: две строки собраны в содержимом, сторона —
               в виде сообщения, как в телеграме. */
            String всё = message.getContent() == null ? "" : message.getContent();
            int разрыв = всё.indexOf('\n');
            String заголовок = разрыв < 0 ? всё : всё.substring(0, разрыв);
            String подпись = разрыв < 0 ? "" : всё.substring(разрыв + 1);
            boolean моё = "call_out".equals(message.getMessageType());
            boolean пропущен = заголовок.contains("Missed") || заголовок.contains("Cancelled");
            holder.callCardText.setText(заголовок);
            holder.callCardText.setTypeface(null, android.graphics.Typeface.BOLD);
            holder.callCardText.setTextColor(пропущен ? 0xFFFCA5A5 : 0xFFF1F5F9);
            if (holder.callCardSub != null) {
                holder.callCardSub.setText(подпись);
                android.graphics.drawable.Drawable стрелка =
                        androidx.core.content.ContextCompat.getDrawable(context,
                                моё ? R.drawable.ic_call_made : R.drawable.ic_call_received);
                if (стрелка != null) {
                    стрелка = стрелка.mutate();
                    стрелка.setTint(пропущен ? 0xFFF87171 : 0xFF4ADE80);
                    int размер = (int) (16 * context.getResources().getDisplayMetrics().density);
                    стрелка.setBounds(0, 0, размер, размер);
                }
                holder.callCardSub.setCompoundDrawables(стрелка, null, null, null);
            }
            if (holder.callCardIcon != null) {
                /* Трубка — кнопка: звоним снова тем же видом связи. Пока
                   идёт выбор, нажатие на неё выделяет строку, как и везде. */
                holder.callCardIcon.setOnClickListener(в -> {
                    if (выбор != null && выбор.идёт()) { выбор.переключить(message); return; }
                    if (слушательЗвонков != null) слушательЗвонков.позвонить(message);
                });
                boolean видео = заголовок.startsWith("Video");
                holder.callCardIcon.setImageResource(видео ? R.drawable.ic_video_call : R.drawable.ic_calls);
            }
            if (holder.callCardBubble != null) {
                /* Карточка звонка живёт в ленте наравне с сообщениями:
                   долгое нажатие выделяет её, нажатие в режиме выбора
                   добавляет и снимает. */
                holder.callCardBubble.setOnLongClickListener(в -> {
                    if (выбор != null) { выбор.начать(message); return true; }
                    if (слушательЗвонков != null) слушательЗвонков.убрать(message);
                    return true;
                });
                holder.callCardBubble.setOnClickListener(в -> {
                    if (выбор != null && выбор.идёт()) выбор.переключить(message);
                });
                /* Подряд идущие звонки — так же теснее, как сообщения. */
                развестиСтроки(holder.itemView, position);
                /* И по всей ширине строки, а не только по самой карточке —
                   как у сообщений и как в телеграме. */
                holder.itemView.setOnLongClickListener(в -> {
                    if (выбор != null) { выбор.начать(message); return true; }
                    return false;
                });
                holder.itemView.setOnClickListener(в -> {
                    if (выбор != null && выбор.идёт()) выбор.переключить(message);
                });
                полосаВыделения(holder.itemView, выбор != null && выбор.выбрано(message));
                кружокВыбора(holder.itemView, message);
                android.widget.FrameLayout.LayoutParams лп =
                        (android.widget.FrameLayout.LayoutParams) holder.callCardBubble.getLayoutParams();
                лп.gravity = моё ? android.view.Gravity.END : android.view.Gravity.START;
                /* Входящая карточка стоит слева — отодвигаем её под кружок,
                   исходящая прижата к правому краю и остаётся на месте. */
                лп.setMarginStart(моё ? 0 : местоПодКружок(
                        holder.itemView.getResources().getDisplayMetrics().density));
                holder.callCardBubble.setLayoutParams(лп);
                holder.callCardBubble.setBackgroundResource(
                        моё ? R.drawable.bg_call_card_mine : R.drawable.bg_call_card);
            }
            return;
        }
        if (message == null || message.getSender() == null) {
            Log.w(TAG, "Message or sender is null at position: " + position);
            return;
        }
        holder.bind(message, position);
        // ДОБАВЛЕНО: Остановка предыдущего воспроизведения при привязке нового элемента, включая TTS
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
            Log.d(TAG, "MediaPlayer released for message id=" + message.getId());
        }
        stopCurrentTTS(); // ДОБАВЛЕНО: Остановка TTS для предотвращения наложения аудио
    }

    public void setTranslationEnabled(boolean enabled) {
        this.isTranslationEnabled = enabled;
        notifyDataSetChanged();
    }

    // Установка состояния TTS
    public void setTTSEnabled(boolean enabled) {
        this.isTTSEnabled = enabled;
        notifyDataSetChanged();
    }

    // ДОБАВЛЕНО: Сеттер для isTranscriptionEnabled
    public void setTranscriptionEnabled(boolean enabled) {
        this.isTranscriptionEnabled = enabled;
        notifyDataSetChanged();
        Log.d(TAG, "Transcription enabled set to: " + enabled);
    }


    /** Первая буква имени для кружка-заглушки. */
    private static String initialOf(String name) {
        return (name == null || name.trim().isEmpty())
                ? "?" : name.trim().substring(0, 1).toUpperCase();
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    public void updateMessages(List<Message> newMessages) {
        if (newMessages == null) {
            newMessages = new ArrayList<>();
        }
        Log.d(TAG, "Updating messages: oldSize=" + messages.size() + ", newSize=" + newMessages.size());

        // Сохраняем переводы и транскрипции из старого списка
        Map<Long, String> translationMap = new HashMap<>();
        Map<Long, String> languageMap = new HashMap<>();
        Map<Long, String> transcriptionMap = new HashMap<>();
        for (Message oldMessage : messages) {
            if (oldMessage.getTranslatedContent() != null && !oldMessage.getTranslatedContent().isEmpty()) {
                translationMap.put(oldMessage.getId(), oldMessage.getTranslatedContent());
                languageMap.put(oldMessage.getId(), oldMessage.getTranslationLanguage());
            }
            if (oldMessage.getTranscribedContent() != null && !oldMessage.getTranscribedContent().isEmpty()) {
                transcriptionMap.put(oldMessage.getId(), oldMessage.getTranscribedContent());
            }
        }

        // Обновляем новый список, сохраняя переводы и транскрипции, и устанавливаем showOriginal = false для аудиосообщений
        for (Message newMessage : newMessages) {
            // Перевод из прежнего списка подставляем, только если с сервера
            // его не прислали: иначе старый перевод (а он может быть на
            // прежнем языке чата) затирал свежий, и в хинди-чате висел
            // английский
            boolean fresh = newMessage.getTranslatedContent() != null
                    && !newMessage.getTranslatedContent().isEmpty();
            if (!fresh && translationMap.containsKey(newMessage.getId())) {
                newMessage.setTranslatedContent(translationMap.get(newMessage.getId()));
                newMessage.setTranslationLanguage(languageMap.get(newMessage.getId()));
            }
            if (transcriptionMap.containsKey(newMessage.getId())) {
                newMessage.setTranscribedContent(transcriptionMap.get(newMessage.getId()));
            }
            // Устанавливаем showOriginal = false только для аудиосообщений с переводом
            if ("audio".equalsIgnoreCase(newMessage.getMessageType()) &&
                    newMessage.getTranslatedContent() != null && !newMessage.getTranslatedContent().isEmpty()) {
                newMessage.setShowOriginal(false);
                Log.d(TAG, "Set showOriginal=false for audio message id=" + newMessage.getId());
            }
        }

        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new MessageDiffCallback(messages, newMessages));
        messages.clear();
        messages.addAll(newMessages);
        diffResult.dispatchUpdatesTo(this);
        Log.d(TAG, "Messages updated with DiffUtil, dispatched changes");
    }

    // Метод для обновления статуса пользователя
    public void updateUserStatus(long userId, boolean online) {
        //Log.d(TAG, "Updating user status: userId=" + userId + ", online=" + online);
        userStatuses.put(userId, online);
        //Log.d(TAG, "Current userStatuses: " + userStatuses.toString());
        int updatedCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getSender() != null && messages.get(i).getSender().getId() == userId) {
                //Log.d(TAG, "Notifying item changed at position=" + i + " for userId=" + userId + ", messageId=" + messages.get(i).getId());
                notifyItemChanged(i);
                updatedCount++;
            }
        }
        Log.d(TAG, "Updated status for userId=" + userId + ", online=" + online + ", updated items=" + updatedCount);
    }

    public void playTTS(String text, String language, long messageId, boolean isTranslated) {
        // Останавливаем текущее воспроизведение, если оно есть
        stopCurrentTTS();

        // Проверяем, пытаемся ли воспроизвести то же сообщение
        if (currentPlayingMessageId == messageId && isPlayingTranslated == isTranslated) {
            Log.d(TAG, "TTS already playing for message id=" + messageId + ", translated=" + isTranslated);
            return;
        }

        ApiService apiService = ApiClient.getApiService();
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("text", text);
            requestBody.put("language", language);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating TTS request JSON: " + e.getMessage());
            Toast.makeText(context, "Failed to prepare TTS request", Toast.LENGTH_SHORT).show();
            return;
        }

        RequestBody body = RequestBody.create(MediaType.parse("application/json"), requestBody.toString());
        apiService.generateTTS(body).enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String jsonResponse = response.body().string();
                        JSONObject json = new JSONObject(jsonResponse);
                        String audioBase64 = json.optString("audio", null);
                        if (audioBase64 != null && !audioBase64.isEmpty()) {
                            byte[] audioData = Base64.decode(audioBase64, Base64.DEFAULT);
                            File tempFile = File.createTempFile("tts_" + messageId, ".mp3", context.getCacheDir());
                            FileOutputStream fos = new FileOutputStream(tempFile);
                            fos.write(audioData);
                            fos.close();

                            currentMediaPlayer = new MediaPlayer();
                            currentMediaPlayer.setDataSource(tempFile.getAbsolutePath());
                            currentMediaPlayer.setOnPreparedListener(mp -> {
                                currentMediaPlayer.start();
                                currentPlayingMessageId = messageId;
                                isPlayingTranslated = isTranslated;
                                Log.d(TAG, "TTS started for message id=" + messageId + ", translated=" + isTranslated);
                            });
                            currentMediaPlayer.setOnCompletionListener(mp -> {
                                stopCurrentTTS();
                                if (tempFile.exists()) {
                                    tempFile.delete();
                                }
                                Log.d(TAG, "TTS playback completed for message id=" + messageId);
                            });
                            currentMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                                Log.e(TAG, "TTS playback error for message id=" + messageId + ": what=" + what + ", extra=" + extra);
                                Toast.makeText(context, "Failed to play TTS audio", Toast.LENGTH_SHORT).show();
                                stopCurrentTTS();
                                if (tempFile.exists()) {
                                    tempFile.delete();
                                }
                                return true;
                            });
                            currentMediaPlayer.prepareAsync();
                        } else {
                            Log.e(TAG, "No audio data in TTS response for message id=" + messageId);
                            Toast.makeText(context, "Failed to generate TTS audio", Toast.LENGTH_SHORT).show();
                        }
                    } catch (IOException | JSONException e) {
                        Log.e(TAG, "Error processing TTS response for message id=" + messageId + ": " + e.getMessage());
                        Toast.makeText(context, "Failed to process TTS audio", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    Log.e(TAG, "TTS API request failed for message id=" + messageId + ": code=" + response.code());
                    Toast.makeText(context, "Failed to generate TTS audio", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Log.e(TAG, "TTS request failed for message id=" + messageId + ": " + t.getMessage());
                Toast.makeText(context, "Failed to generate TTS audio", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void stopCurrentTTS() {
        if (currentMediaPlayer != null) {
            try {
                if (currentMediaPlayer.isPlaying()) {
                    currentMediaPlayer.stop();
                }
                currentMediaPlayer.reset();
                currentMediaPlayer.release();
                Log.d(TAG, "TTS stopped for message id=" + currentPlayingMessageId);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping MediaPlayer: " + e.getMessage());
            }
            currentMediaPlayer = null;
            currentPlayingMessageId = -1;
            isPlayingTranslated = false;
        }
    }

    public class MessageViewHolder extends RecyclerView.ViewHolder {
        ImageView avatarImageView;
        View avatarPlaceholder, statusIndicator;
        MaterialCardView messageContainer;
        TextView contentTextView, timestampTextView;
        TextView daySeparator;
        TextView attachmentUnavailable;
        View fileLinkContainer;
        LinearLayout indicatorsContainer;
        TextView translatedContentTextView;
        ImageView deliveryIndicator, readIndicator;
        LinearLayout audioContainer;
        ImageView playPauseButton;
        ImageView translateIcon;
        TextView audioDurationTextView;
        // Добавлено: SeekBar и TextView для текущего времени
        WaveformSeekBar audioSeekBar;
        ImageView ttsIcon; // Иконка TTS для оригинального контента
        private Handler seekBarHandler = new Handler(); // Handler для обновления прогресса SeekBar
        private Runnable seekBarUpdater;
        private int currentPosition = 0; // Переменная для хранения позиции паузы
        private boolean isPrepared = false; // Флаг для отслеживания состояния подготовки MediaPlayer
        ImageView ttsTranslatedIcon; // Иконка TTS для переведенного контента
        ImageView transcriptionIcon; // Поле для иконки транскрипции
        TextView transcribedContentTextView; // ДОБАВЛЕНО: Поле для текста транскрипции
        ImageView translateIconAudio;
        ImageView ttsIconAudio;
        ImageView ttsTranslatedIconAudio;
        ImageView imagePreview;
        View imageCard; // карточка со скруглением вокруг снимка // *** ДОБАВЛЕНО: Превью изображения для файлов в личном чате ***
        // ДОБАВЛЕНО: Превью ссылок
        CardView linkPreviewCard;
        ImageView previewImage;
        TextView previewTitle;
        TextView previewDescription;
        TextView previewUrl;
        private long boundMessageId = -1;  // ID текущего привязанного сообщения

        TextView callCardText, callCardSub;   // есть только у карточки звонка
        View callCardBubble;
        ImageView callCardIcon;

        public MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            callCardText = itemView.findViewById(R.id.call_card_text);
            callCardSub = itemView.findViewById(R.id.call_card_sub);
            callCardBubble = itemView.findViewById(R.id.call_card_bubble);
            callCardIcon = itemView.findViewById(R.id.call_card_icon);
            if (callCardText != null) {
                /* Карточка звонка: ни пузыря, ни аватара, ни дорожки — дальше
                   искать нечего, а трогать ненайденное нельзя. */
                return;
            }
            avatarImageView = itemView.findViewById(R.id.avatar_image_view);
            avatarPlaceholder = itemView.findViewById(R.id.avatar_placeholder);
            messageContainer = itemView.findViewById(R.id.message_container);
            contentTextView = itemView.findViewById(R.id.content_text_view);
            fileLinkContainer = itemView.findViewById(R.id.file_link_container);
            timestampTextView = itemView.findViewById(R.id.timestamp_text_view);
            attachmentUnavailable = itemView.findViewById(R.id.attachment_unavailable);
            daySeparator = itemView.findViewById(R.id.day_separator);
            indicatorsContainer = itemView.findViewById(R.id.indicators_container);
            deliveryIndicator = itemView.findViewById(R.id.delivery_indicator);
            readIndicator = itemView.findViewById(R.id.read_indicator);
            statusIndicator = itemView.findViewById(R.id.status_indicator);
            translatedContentTextView = itemView.findViewById(R.id.translated_content_text_view);
            audioContainer = itemView.findViewById(R.id.audio_container);
            playPauseButton = itemView.findViewById(R.id.play_pause_button);
            audioDurationTextView = itemView.findViewById(R.id.audio_duration_text_view);
            audioSeekBar = itemView.findViewById(R.id.audio_seek_bar);
            translateIcon = itemView.findViewById(R.id.translate_icon);
            // Инициализация иконок TTS
            ttsIcon = itemView.findViewById(R.id.tts_icon);
            ttsTranslatedIcon = itemView.findViewById(R.id.tts_translated_icon);
            // Инициализация иконки и текста транскрипции
            transcriptionIcon = itemView.findViewById(R.id.transcription_icon);
            transcribedContentTextView = itemView.findViewById(R.id.transcribed_content_text_view);
            // **ДОБАВЛЕНО**: Инициализация новых иконок для аудиосообщений
            translateIconAudio = itemView.findViewById(R.id.translate_icon_audio);
            ttsIconAudio = itemView.findViewById(R.id.tts_icon_audio);
            ttsTranslatedIconAudio = itemView.findViewById(R.id.tts_translated_icon_audio);
            imagePreview = itemView.findViewById(R.id.image_preview);
            PhotoBubble.keepInShape(imagePreview);
            imageCard = itemView.findViewById(R.id.image_card); // *** ДОБАВЛЕНО: Превью изображения ***
            // ДОБАВЛЕНО: Превью ссылок
            linkPreviewCard = itemView.findViewById(R.id.link_preview_card);
            previewImage = itemView.findViewById(R.id.preview_image);
            previewTitle = itemView.findViewById(R.id.preview_title);
            previewDescription = itemView.findViewById(R.id.preview_description);
            previewUrl = itemView.findViewById(R.id.preview_url);

            // Обработчик клика для переключения перевода
            messageContainer.setOnClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    Message message = messages.get(position);
                    /* Идёт выбор — нажатие добавляет и снимает выделение. */
                    if (выбор != null && выбор.идёт()) {
                        выбор.переключить(message);
                        return;
                    }
                    /* Переключать нечего — показываем меню: иначе нажатие
                       пропадает впустую. */
                    /* Переключать есть что, только когда перевод включён и
                       вправду пришёл; иначе касание было бы впустую. */
                    boolean естьПеревод = isTranslationEnabled
                            && message.getTranslatedContent() != null
                            && !message.getTranslatedContent().isEmpty();
                    boolean естьРасшифровка = isTranscriptionEnabled
                            && message.getTranscribedContent() != null
                            && !message.getTranscribedContent().isEmpty();
                    boolean естьЧтоПереключить = естьПеревод || естьРасшифровка;
                    if (!естьЧтоПереключить) {
                        показатьМеню(v, message);
                        return;
                    }
                    if (message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) {
                        message.setShowOriginal(!message.isShowOriginal());
                        if ("audio".equalsIgnoreCase(message.getMessageType()) && isTranscriptionEnabled && message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty()) {
                            // Для аудиосообщений переключаем между транскрипцией и переводом
                            contentTextView.setText(message.isShowOriginal() ? message.getTranscribedContent() : message.getTranslatedContent());
                            translatedContentTextView.setVisibility(View.GONE); // Скрываем перевод внизу
                            Log.d(TAG, "Toggled audio message id=" + message.getId() + ", showOriginal=" + message.isShowOriginal() + ", text=" + contentTextView.getText());
                        } else {
                            // Для текстовых сообщений переключаем между оригиналом и переводом
                            contentTextView.setText(message.isShowOriginal() ? message.getContent() : message.getTranslatedContent());
                            Log.d(TAG, "Toggled text message id=" + message.getId() + ", showOriginal=" + message.isShowOriginal() + ", text=" + contentTextView.getText());
                        }
                        notifyItemChanged(position);
                    } else {
                        Log.d(TAG, "Translation toggle skipped for message id=" + message.getId() +
                                ", hasTranslation=" + (message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) +
                                ", isAudio=" + "audio".equalsIgnoreCase(message.getMessageType()));
                    }
                }
            });

            /* Долгое нажатие начинает выбор — как в телеграме. */
            messageContainer.setOnLongClickListener(v -> {
                int position = getAdapterPosition();
                if (position != RecyclerView.NO_POSITION && выбор != null) {
                    выбор.начать(messages.get(position));
                    return true;
                }
                return false;
            });
        }

        /** Прежнее меню над сообщением: копировать, править, убрать. */
        private void показатьМеню(View v, Message message) {
            boolean mine = message.getSender() != null && message.getSender().getId() == currentUserId;
            {

                        ListPopupWindow listPopupWindow = new ListPopupWindow(context);
                        listPopupWindow.setAnchorView(v);
                        listPopupWindow.setModal(true);
                        listPopupWindow.setDropDownGravity(Gravity.END);
                        listPopupWindow.setWidth(520);
                        listPopupWindow.setVerticalOffset(8);

                        listPopupWindow.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.popup_menu_background));

                        List<PopupMenuItem> menuItems = new ArrayList<>();
                        menuItems.add(new PopupMenuItem("Copy", null, Color.parseColor("#E2E8F0")));
                        if (mine) {
                            menuItems.add(new PopupMenuItem("Edit", null, Color.parseColor("#E2E8F0")));
                        }
                        // Убрать можно и чужое: у себя всегда, у собеседника — вдвоём
                        menuItems.add(new PopupMenuItem("Delete", null, Color.parseColor("#FF6B6B")));

                        PopupMenuAdapter adapter = new PopupMenuAdapter(context, menuItems);
                        listPopupWindow.setAdapter(adapter);

                        listPopupWindow.setOnItemClickListener((parent, view, pos, id) -> {
                            String title = menuItems.get(pos).title;
                            if ("Copy".equals(title)) {
                                copyMessageText(context, contentTextView.getText().toString());
                            } else if ("Edit".equals(title)) {
                                actionListener.onEdit(message);
                            } else if ("Delete".equals(title)) {
                                actionListener.onDelete(message);
                            }
                            listPopupWindow.dismiss();
                        });

                        listPopupWindow.show();
            }
        }

        // Добавлено: Публичные методы для обновления UI
        /** Привязан ли холдер именно к этому сообщению. */
        public boolean isBoundTo(long messageId) {
            return boundMessageId == messageId;
        }

        public void setTranslatedContentText(String text) {
            int position = getAdapterPosition();
            if (position != RecyclerView.NO_POSITION) {
                Message message = messages.get(position);
                if ("audio".equalsIgnoreCase(message.getMessageType())) {
                    // Для аудиосообщений: устанавливаем перевод в contentTextView и скрываем translatedContentTextView
                    Log.w(TAG, "setTranslatedContentText called for audio message id=" + message.getId() + ", redirecting to contentTextView");
                    contentTextView.setText(text);
                    translatedContentTextView.setVisibility(View.GONE);
                    message.setShowOriginal(false);
                } else {
                    // Для текстовых сообщений: показываем перевод в translatedContentTextView
                    translatedContentTextView.setText(text);
                    translatedContentTextView.setVisibility(View.VISIBLE);
                    Log.d(TAG, "setTranslatedContentText for non-audio message id=" + message.getId() + ": text=" + text + ", translatedContentTextView=VISIBLE");
                }
            } else {
                Log.w(TAG, "setTranslatedContentText called with invalid position");
            }
            applyFooterLayout(); // текст сменился — хвост под подвал надо дописать заново
        }

        public void setContentText(String text) {
            contentTextView.setText(text);
            contentTextView.setVisibility(View.VISIBLE);
            applyFooterLayout(); // текст сменился — хвост под подвал надо дописать заново
            Log.d(TAG, "Set content text: " + text);
        }

        public void setTranslateIconVisibility(int visibility) {
            translateIcon.setVisibility(visibility);
            Log.d(TAG, "Set translate icon visibility: " + (visibility == View.VISIBLE ? "VISIBLE" : "GONE"));
        }

        public void hideTranslatedContentTextView() {
            translatedContentTextView.setVisibility(View.GONE);
            applyFooterLayout(); // строка пропала — подвал пересчитываем
            Log.d(TAG, "hideTranslatedContentTextView called for message id=" +
                    (getAdapterPosition() != RecyclerView.NO_POSITION ? messages.get(getAdapterPosition()).getId() : "unknown"));
        }

        /** Пока привязка не закончилась, подвал считать рано: строки ещё

         * меняются, и пузырь успел бы показаться то шире, то уже. */

        private boolean binding;


        public void bind(Message message, int position) {
            binding = true;
            try {
                bindContents(message, position);
                /* Пузырь полученного темнее полосы выделения и смотрелся в
                   ней дырой — пока выбран, поднимаем и его тон. */
                if (messageContainer != null && выбор != null && выбор.выбрано(message)
                        && message.getSender() != null
                        && message.getSender().getId() != currentUserId) {
                    messageContainer.setCardBackgroundColor(0xFF24344F);
                }
            } finally {
                // Подвал считаем один раз, когда все строки уже на местах
                binding = false;
                applyFooterLayout();
            }
        }

        private void bindContents(Message message, int position) {
            развестиСтроки(itemView, position);
            /* Нажатие ловим всей строкой, а не только пузырём: в телеграме
               выделяют касанием где угодно по ширине экрана. */
            itemView.setOnLongClickListener(в -> {
                if (выбор != null) { выбор.начать(message); return true; }
                return false;
            });
            itemView.setOnClickListener(в -> {
                if (выбор != null && выбор.идёт()) выбор.переключить(message);
            });

            /* Выбранное подсвечиваем — видно, что попало в выбор. */
            /* Полоса обнимает пузырь, а не весь промежуток между строками. */
            bindDaySeparator(position); // дата дня над первым сообщением дня
            отступПодДатой(itemView);
            полосаВыделения(itemView, выбор != null && выбор.выбрано(message));
            кружокВыбора(itemView, message);
            User sender = message.getSender();
            boolean isSentByCurrentUser = sender.getId() == currentUserId;

            Log.d(TAG, "Binding message id=" + message.getId() + ", fileUrl=" + message.getFileUrl() +
                    ", delivered=" + message.isDelivered() + ", read=" + message.isRead() +
                    ", senderId=" + sender.getId() + ", messageType=" + message.getMessageType() +
                    ", isTranslationEnabled=" + isTranslationEnabled + ", isTTSEnabled=" + isTTSEnabled +
                    ", isTranscriptionEnabled=" + isTranscriptionEnabled);

            boundMessageId = message.getId();  // Запоминаем ID текущего сообщения

            // ЯВНО скрываем image_preview в начале bind — чтобы старое превью не оставалось
            imagePreview.setVisibility(View.GONE);
            if (imageCard != null) imageCard.setVisibility(View.GONE);
            imagePreview.setOnClickListener(null); // убираем старый обработчик
            // Точка «в сети» живёт у края аватара: без него её показывать негде,
            // а от прошлого сообщения в этой же вьюхе она уезжала к краю экрана
            statusIndicator.setVisibility(View.GONE);
            // Подпись о пропавшем вложении тоже принадлежит прошлому сообщению
            if (attachmentUnavailable != null) attachmentUnavailable.setVisibility(View.GONE);
            // Значки аудио живут в подвале рядом с обычными: у текстового
            // сообщения их быть не должно
            if (transcriptionIcon != null) transcriptionIcon.setVisibility(View.GONE);
            if (translateIconAudio != null) translateIconAudio.setVisibility(View.GONE);
            if (ttsIconAudio != null) ttsIconAudio.setVisibility(View.GONE);
            if (ttsTranslatedIconAudio != null) ttsTranslatedIconAudio.setVisibility(View.GONE);

            // И строка расшифровки: у текстового сообщения её быть не должно, а
            // оставшись от аудио, она забирала себе хвост под подвал
            if (transcribedContentTextView != null) transcribedContentTextView.setVisibility(View.GONE);
            // Отменяем загрузку от прошлого сообщения, иначе чужая картинка
            // успевает мелькнуть в переиспользованной вьюхе
            стекло(context).clear(imagePreview);

            // Установка выравнивания и фона
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) messageContainer.getLayoutParams();

            // Вычисляем ширину экрана и отступы
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            float density = context.getResources().getDisplayMetrics().density;
            int marginHorizontal = (int) (5 * density);   // от края экрана до пузыря
            int padding = (int) (12 * density); // padding="12dp" в LinearLayout
            int avatarWidth = (int) (32 * density); // Ширина аватара
            int marginStart = (int) (5 * density);    // столько же слева
            int visualMargin = (int) (64 * density); // Отступ 56dp для визуального различия

            if (isSentByCurrentUser) {
                // Отправленные сообщения: выравнивание по правому краю
                params.startToStart = ConstraintLayout.LayoutParams.UNSET;
                params.startToEnd = ConstraintLayout.LayoutParams.UNSET;
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
                params.rightMargin = marginHorizontal; // Отступ 8dp от правого края
                params.leftMargin = visualMargin; // Отступ 56dp от левого края
                params.constrainedWidth = true; // Включаем ограничение ширины
                // Ограничиваем ширину
                int maxWidthPx = screenWidth - 2 * marginHorizontal - 2 * padding - marginHorizontal - visualMargin;
                params.matchConstraintMaxWidth = maxWidthPx;
                messageContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.sent_message_background));
                // Проверяем, является ли сообщение первым в серии своих сообщений
                /* Прямой угол — у последнего сообщения череды и снизу: так
                   в телеграме, уголок будто указывает на говорящего. */
                boolean showSquareCorner = ктоНиже(position) != sender.getId();
                if (showSquareCorner) {
                    // Квадратный верхний правый угол для первого сообщения в серии
                    ShapeAppearanceModel shapeModel = new ShapeAppearanceModel()
                            .toBuilder()
                            .setBottomRightCorner(CornerFamily.ROUNDED, 0f)
                            .setTopLeftCorner(CornerFamily.ROUNDED, 12f * density)
                            .setTopRightCorner(CornerFamily.ROUNDED, 12f * density)
                            .setBottomLeftCorner(CornerFamily.ROUNDED, 12f * density)
                            .build();
                    messageContainer.setShapeAppearanceModel(shapeModel);
                    PhotoBubble.matchBubbleCorner(imagePreview, PhotoBubble.SQUARE_BOTTOM_RIGHT);
                    Log.d(TAG, "Sent message with square top-right corner at position " + position + ": set top-right=0dp, others=16dp");
                } else {
                    // Скругленные углы для последующих сообщений в серии
                    ShapeAppearanceModel shapeModel = new ShapeAppearanceModel()
                            .toBuilder()
                            .setAllCorners(CornerFamily.ROUNDED, 12f * density)
                            .build();
                    messageContainer.setShapeAppearanceModel(shapeModel);
                    PhotoBubble.matchBubbleCorner(imagePreview, PhotoBubble.SQUARE_NONE);
                    Log.d(TAG, "Sent message with rounded corners at position " + position + ": set corner radius to 16dp");
                }
                avatarImageView.setVisibility(View.GONE);
                avatarPlaceholder.setVisibility(View.GONE);
                Log.d(TAG, "Sent message at position " + position + ": maxWidth=" + maxWidthPx + ", leftMargin=" + visualMargin + ", rightMargin=" + marginHorizontal);
            } else {
                // Полученные сообщения: выравнивание по аватару
                params.startToStart = ConstraintLayout.LayoutParams.UNSET;
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET;
                params.rightMargin = marginHorizontal; // Добавляем отступ от правого края (8dp)
                params.leftMargin = 0; // Сбрасываем leftMargin
                params.setMarginStart(marginStart + местоПодКружок(density));   // место под кружок выбора // Явно задаем marginStart = 12dp
                params.constrainedWidth = true;
                // Ограничиваем максимальную ширину, учитывая marginStart
                // Места под кружок больше нет — строке достаётся его ширина
                int maxWidthPx = screenWidth - marginHorizontal - marginStart - visualMargin - 2 * padding;
                params.matchConstraintMaxWidth = maxWidthPx;

                /* Аватарку в переписке вдвоём не показываем вовсе: собеседник
                   один и он же в шапке. По первому в череде по-прежнему
                   срезаем верхний угол — он и указывает на начало реплики. */
                boolean первыйВЧереде = ктоНиже(position) != sender.getId();
                params.startToEnd = R.id.avatar_placeholder;
                avatarImageView.setVisibility(View.GONE);
                avatarPlaceholder.setVisibility(View.VISIBLE);
                    /* Кружка нет — и места под него не держим: строка шире. */
                    android.view.ViewGroup.LayoutParams мера = avatarPlaceholder.getLayoutParams();
                    if (мера != null && мера.width != 0) {
                        мера.width = 0;
                        avatarPlaceholder.setLayoutParams(мера);
                    }
                if (первыйВЧереде) {
                    // Квадратный верхний левый угол
                    ShapeAppearanceModel shapeModel = new ShapeAppearanceModel()
                            .toBuilder()
                            .setBottomLeftCorner(CornerFamily.ROUNDED, 0f)
                            .setTopLeftCorner(CornerFamily.ROUNDED, 12f * density)
                            .setTopRightCorner(CornerFamily.ROUNDED, 12f * density)
                            .setBottomRightCorner(CornerFamily.ROUNDED, 12f * density)
                            .build();
                    messageContainer.setShapeAppearanceModel(shapeModel);
                    PhotoBubble.matchBubbleCorner(imagePreview, PhotoBubble.SQUARE_BOTTOM_LEFT);
                    Log.d(TAG, "Received message with avatar at position " + position + ": set top-left=0dp, others=16dp, marginStart=" + marginStart);
                } else {
                    // Скругленные углы
                    ShapeAppearanceModel shapeModel = new ShapeAppearanceModel()
                            .toBuilder()
                            .setAllCorners(CornerFamily.ROUNDED, 12f * density)
                            .build();
                    messageContainer.setShapeAppearanceModel(shapeModel);
                    PhotoBubble.matchBubbleCorner(imagePreview, PhotoBubble.SQUARE_NONE);
                    Log.d(TAG, "Received message without avatar at position " + position + ": set corner radius to 16dp, marginStart=" + marginStart);
                }
                messageContainer.setCardBackgroundColor(ContextCompat.getColor(context, R.color.received_message_background));
                Log.d(TAG, "Received message at position " + position + ": maxWidth=" + maxWidthPx + ", marginStart=" + marginStart);
            }
            messageContainer.setLayoutParams(params);

            // Логирование фактической ширины и положения после рендеринга
            messageContainer.post(() -> {
                int leftEdgeDistance = isSentByCurrentUser ?
                        messageContainer.getLeft() - visualMargin :
                        messageContainer.getLeft() - (marginHorizontal + avatarWidth + params.getMarginStart());
                int rightEdgeDistance = isSentByCurrentUser ?
                        (screenWidth - (messageContainer.getRight() + marginHorizontal)) :
                        (screenWidth - messageContainer.getRight() - visualMargin);
                Log.d(TAG, "Message at position " + position + ": actualWidth=" + messageContainer.getWidth() +
                        ", maxWidth=" + params.matchConstraintMaxWidth +
                        ", marginStart=" + params.getMarginStart() +
                        ", leftEdgeDistance=" + leftEdgeDistance + "px (should be ~0px for received)" +
                        ", rightEdgeDistance=" + rightEdgeDistance + "px (should be ~0px for sent or long received)" +
                        ", textContent=" + contentTextView.getText());
            });

            // Настройка аватара
            if (!isSentByCurrentUser && avatarImageView.getVisibility() == View.VISIBLE) {
                String avatarUrl = sender.getAvatar();
                if (!TextUtils.isEmpty(avatarUrl)) {
                    стекло(context)
                            .load("https://unlimitedpossibilities12.org" + avatarUrl)
                            .apply(new RequestOptions()
                                    .circleCrop()
                                    // Файл аватара мог пропасть с сервера — тогда
                                    // кружок с буквой, а не безликий серый
                                    .placeholder(new LetterAvatarDrawable(initialOf(sender.getUsername())))
                                    .error(new LetterAvatarDrawable(initialOf(sender.getUsername()))))
                            .into(avatarImageView);
                } else {
                    String initial = TextUtils.isEmpty(sender.getUsername()) ? "?" : sender.getUsername().substring(0, 1).toUpperCase();
                    LetterAvatarDrawable avatarDrawable = new LetterAvatarDrawable(initial);
                    avatarImageView.setImageDrawable(avatarDrawable);
                }

                // Управление индикатором статуса
                Long senderId = (long) sender.getId();
                Boolean isOnline = userStatuses.get(senderId);
                boolean isVisible = isOnline != null && isOnline;
                statusIndicator.setVisibility(isVisible ? View.VISIBLE : View.GONE);
                Log.d(TAG, "Setting status indicator for userId=" + senderId + ", isOnline=" + isOnline +
                        ", visibility=" + (isVisible ? "VISIBLE" : "GONE") + ", messageId=" + message.getId());

                avatarImageView.setOnClickListener(v -> {
                    if (sender.getId() != -1 && context instanceof Activity && !((Activity) context).isFinishing()) {
                        Intent intent = new Intent(context, UserProfileActivity.class);
                        intent.putExtra("userId", sender.getId());
                        context.startActivity(intent);
                        Log.d(TAG, "Avatar clicked: Opening UserProfileActivity for userId=" + sender.getId());
                    } else {
                        Toast.makeText(context, "User profile not available", Toast.LENGTH_SHORT).show();
                        Log.w(TAG, "Avatar click ignored: Invalid senderId=" + sender.getId() + " or activity is finishing");
                    }
                });
            } else {
                avatarImageView.setOnClickListener(null);
            }

            // Обработка аудиосообщений и файлов
            String fileUrl = message.getFileUrl();
            String messageType = message.getMessageType();
            if ("audio".equalsIgnoreCase(messageType) &&
                    messageType != null && !messageType.trim().isEmpty() &&
                    fileUrl != null && !fileUrl.trim().isEmpty() && !fileUrl.equalsIgnoreCase("null")) {
                Log.d("MessageAdapter", "Showing audioContainer for message id=" + message.getId());
                fileLinkContainer.setVisibility(View.GONE);
                audioContainer.setVisibility(View.VISIBLE);
                audioContainer.post(() -> {
                    Log.d("MessageAdapter", "audioContainer visibility for message id=" + message.getId() + ": " + (audioContainer.getVisibility() == View.VISIBLE ? "VISIBLE" : "GONE"));
                });
                playPauseButton.setImageResource(R.drawable.ic_play);
                // Как в телефонных мессенджерах: в подписи стоит длина
                // записи, а при прослушивании на её месте идёт счётчик
                audioSeekBar.setProgress(0);
                audioSeekBar.setEnabled(false);

                // Звуковая волна вместо полоски: цвета под свой или чужой
                // пузырь, сама волна из кэша, а если её ещё не считали —
                // посчитаем в фоне и дорисуем, когда будет готова
                final boolean ownVoice = isSentByCurrentUser;
                final String waveUrl = fileUrl;
                audioSeekBar.setWaveColors(ownVoice ? 0xFFFFFFFF : 0xFF67E8F9,
                        ownVoice ? 0x66FFFFFF : 0xFF64748B);
                audioSeekBar.setTag(R.id.audio_seek_bar, waveUrl);
                audioSeekBar.setLevels(Waveforms.cached(context, waveUrl));
                Waveforms.load(context, waveUrl, levels -> {
                    if (waveUrl.equals(audioSeekBar.getTag(R.id.audio_seek_bar))) {
                        audioSeekBar.setLevels(levels);
                    }
                });

                // Длину записи, если её уже мерили, показываем сразу: иначе при
                // каждой привязке время подмигивало многоточием
                int knownDuration = AudioDurations.get(context, fileUrl);
                voiceDurationMs = knownDuration;
                if (knownDuration > 0) {
                    showVoiceTime(knownDuration);
                    audioSeekBar.setMax(knownDuration);
                } else {
                    audioDurationTextView.setText("…");
                }

                // Запись могла пропасть с сервера вместе с остальными файлами:
                // мёртвый плеер с нулями честнее заменить строкой
                final boolean voiceGone = MissingAttachments.isMissing(context, fileUrl);
                audioContainer.setVisibility(voiceGone ? View.GONE : View.VISIBLE);
                if (voiceGone) {
                    attachmentUnavailable.setText("Voice message unavailable");
                    attachmentUnavailable.setVisibility(View.VISIBLE);
                } else if (knownDuration <= 0) {
                    // Асинхронное получение длительности аудиофайла
                    MediaPlayer durationPlayer = new MediaPlayer();
                    try {
                        durationPlayer.setDataSource(fileUrl);
                        durationPlayer.setOnPreparedListener(mp -> {
                            int duration = mp.getDuration();
                            int durationSec = duration / 1000;
                            voiceDurationMs = duration;
                            showVoiceTime(duration);
                            audioSeekBar.setMax(duration);
                            mp.release();
                            AudioDurations.put(context, fileUrl, duration);
                            MissingAttachments.forget(context, fileUrl);
                            applyFooterLayout();
                            Log.d(TAG, "Duration fetched for message id=" + message.getId() + ": " + duration + "ms");
                        });
                        durationPlayer.setOnErrorListener((mp, what, extra) -> {
                            Log.e(TAG, "Error fetching duration for message id=" + message.getId() + ": what=" + what + ", extra=" + extra);
                            mp.release();
                            // Плеер мог споткнуться на чём угодно. Говорим, что
                            // записи нет, только если её и правда нет на сервере.
                            final long askedFor = message.getId();
                            new Thread(() -> {
                                if (!isGoneFromServer(fileUrl)) return;
                                MissingAttachments.remember(context, fileUrl);
                                AudioDurations.forget(context, fileUrl);
                                ((Activity) context).runOnUiThread(() -> {
                                    if (boundMessageId != askedFor) return;
                                    audioContainer.setVisibility(View.GONE);
                                    attachmentUnavailable.setText("Voice message unavailable");
                                    attachmentUnavailable.setVisibility(View.VISIBLE);
                                    applyFooterLayout();
                                });
                            }).start();
                            return true;
                        });
                        durationPlayer.prepareAsync();
                    } catch (IOException e) {
                        Log.e(TAG, "Error setting data source for duration of message id=" + message.getId() + ": " + e.getMessage());
                        audioDurationTextView.setText("0:00");
                        durationPlayer.release();
                    }
                }

                // Обработчик кнопки Play/Pause
                playPauseButton.setColorFilter(ContextCompat.getColor(context, R.color.sent_message_accent));
                playPauseButton.setOnClickListener(v -> {
                    /* Идёт выбор — дорожка и кнопка не играют, а выделяют:
                       иначе по голосовому нельзя попасть пальцем. */
                    if (выбор != null && выбор.идёт()) { выбор.переключить(message); return; }
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        // Сохраняем текущую позицию при паузе
                        currentPosition = mediaPlayer.getCurrentPosition();
                        mediaPlayer.pause();
                        playPauseButton.setImageResource(R.drawable.ic_play);
                        seekBarHandler.removeCallbacks(seekBarUpdater);
                        Log.d(TAG, "Audio paused for message id=" + message.getId() + " at position=" + currentPosition);
                    } else {
                        // Проверяем, существует ли mediaPlayer и подготовлен ли он
                        if (mediaPlayer != null && isPrepared && !mediaPlayer.isPlaying()) {
                            // Возобновляем воспроизведение с сохранённой позиции
                            mediaPlayer.seekTo(currentPosition);
                            mediaPlayer.start();
                            playPauseButton.setImageResource(R.drawable.ic_pause);
                            seekBarHandler.post(seekBarUpdater);
                            Log.d(TAG, "Audio resumed for message id=" + message.getId() + " from position=" + currentPosition);
                        } else {
                            // Создаём новый MediaPlayer, если он не существует или не подготовлен
                            if (mediaPlayer != null) {
                                mediaPlayer.release();
                                mediaPlayer = null;
                            }
                            mediaPlayer = new MediaPlayer();
                            isPrepared = false; // Сбрасываем флаг подготовки
                            try {
                                mediaPlayer.setDataSource(fileUrl);
                                mediaPlayer.setOnPreparedListener(mp -> {
                                    isPrepared = true; // Устанавливаем флаг подготовки
                                    mp.seekTo(currentPosition);
                                    mp.start();
                                    playPauseButton.setImageResource(R.drawable.ic_pause);
                                    int duration = mp.getDuration();
                                    audioSeekBar.setMax(duration);
                                    audioSeekBar.setEnabled(true);
                                    voiceDurationMs = duration;
                                    // Обновляем SeekBar и текущее время
                                    seekBarUpdater = new Runnable() {
                                        @Override
                                        public void run() {
                                            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                                                int currentPosition = mediaPlayer.getCurrentPosition();
                                                audioSeekBar.setProgress(currentPosition);
                                                showVoiceTime(currentPosition);
                                                seekBarHandler.postDelayed(this, 100);
                                            }
                                        }
                                    };
                                    seekBarHandler.post(seekBarUpdater);
                                    Log.d(TAG, "Audio started for message id=" + message.getId() + ", duration=" + duration + ", starting from=" + currentPosition);
                                });
                                mediaPlayer.setOnCompletionListener(mp -> {
                                    playPauseButton.setImageResource(R.drawable.ic_play);
                                    audioSeekBar.setProgress(0);
                                    showVoiceTime(voiceDurationMs);
                                    currentPosition = 0;
                                    isPrepared = false; // Сбрасываем флаг подготовки
                                    seekBarHandler.removeCallbacks(seekBarUpdater);
                                    mp.reset();
                                    Log.d(TAG, "Audio completed for message id=" + message.getId());
                                });
                                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                                    Log.e(TAG, "MediaPlayer error for message id=" + message.getId() + ": what=" + what + ", extra=" + extra);
                                    Toast.makeText(context, "Error playing audio", Toast.LENGTH_SHORT).show();
                                    playPauseButton.setImageResource(R.drawable.ic_play);
                                    audioSeekBar.setProgress(0);
                                    showVoiceTime(voiceDurationMs);
                                    currentPosition = 0;
                                    isPrepared = false; // Сбрасываем флаг подготовки
                                    seekBarHandler.removeCallbacks(seekBarUpdater);
                                    mp.reset();
                                    return true;
                                });
                                mediaPlayer.prepareAsync();
                            } catch (IOException e) {
                                Log.e("MessageAdapter", "Error playing audio for message id=" + message.getId() + ": " + e.getMessage());
                                Toast.makeText(context, "Cannot play audio", Toast.LENGTH_SHORT).show();
                                playPauseButton.setImageResource(R.drawable.ic_play);
                                audioSeekBar.setProgress(0);
                                showVoiceTime(voiceDurationMs);
                                currentPosition = 0;
                                isPrepared = false; // Сбрасываем флаг подготовки
                            }
                        }
                    }
                });

                // Обработчик SeekBar для перемотки
                audioSeekBar.setOnTouchListener((в, событие) -> {
                    if (выбор != null && выбор.идёт()) {
                        if (событие.getAction() == android.view.MotionEvent.ACTION_UP) {
                            выбор.переключить(message);
                        }
                        return true;   // пока идёт выбор, дорожку не трогаем
                    }
                    return false;
                });
                audioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser && mediaPlayer != null && mediaPlayer.isPlaying()) {
                            mediaPlayer.seekTo(progress);
                            showVoiceTime(progress);
                            Log.d(TAG, "SeekBar changed by user for message id=" + message.getId() + ", progress=" + progress);
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                            mediaPlayer.pause();
                            playPauseButton.setImageResource(R.drawable.ic_play);
                            seekBarHandler.removeCallbacks(seekBarUpdater);
                            Log.d(TAG, "SeekBar touch started for message id=" + message.getId());
                        }
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        if (mediaPlayer != null) {
                            mediaPlayer.seekTo(seekBar.getProgress());
                            mediaPlayer.start();
                            playPauseButton.setImageResource(R.drawable.ic_pause);
                            seekBarHandler.post(seekBarUpdater);
                            Log.d(TAG, "SeekBar touch stopped for message id=" + message.getId() + ", seekTo=" + seekBar.getProgress());
                        }
                    }
                });

                // **ДОБАВЛЕНО/ИЗМЕНЕНО**: Обработка иконок в аудиосообщениях
                if (transcriptionIcon != null && translateIconAudio != null && ttsIconAudio != null && ttsTranslatedIconAudio != null) {
                    boolean showTranscriptionIcon = isTranscriptionEnabled && "audio".equalsIgnoreCase(messageType);
                    boolean hasTranscription = message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty();
                    SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
                    long chatId = message.getChat() != null ? message.getChat().getId() : -1;
                    boolean isTranslationEnabledLocal = prefs.getBoolean("translation_enabled_" + chatId, false);
                    boolean isTTSEnabledLocal = prefs.getBoolean("tts_enabled_" + chatId, false);
                    String translationLanguage = prefs.getString("translation_language_" + chatId,
                            AppSettings.defaultTranslationLanguage(context));
                    String translationModeStr = prefs.getString("translation_mode_" + chatId, "ALL");
                    // **ИЗМЕНЕНО**: Финальная инициализация translationMode с использованием временной переменной
                    final TranslationSettingsFragment.TranslationMode translationMode;
                    TranslationSettingsFragment.TranslationMode tempTranslationMode;
                    try {
                        tempTranslationMode = TranslationSettingsFragment.TranslationMode.valueOf(translationModeStr);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Invalid translation mode for message id=" + message.getId() + ": " + translationModeStr + ", defaulting to ALL");
                        tempTranslationMode = TranslationSettingsFragment.TranslationMode.ALL;
                    }
                    translationMode = tempTranslationMode;

                    // Условия показа иконок
                    boolean showTranslateIconAudio = showTranscriptionIcon && isTranslationEnabledLocal && hasTranscription;
                    boolean showTTSIconAudio = showTranscriptionIcon && isTTSEnabledLocal && hasTranscription;
                    boolean showTTSTranslatedIconAudio = showTranscriptionIcon && isTTSEnabledLocal && isTranslationEnabledLocal &&
                            hasTranscription && (translationMode == TranslationSettingsFragment.TranslationMode.ALL ||
                            translationMode == TranslationSettingsFragment.TranslationMode.NEW_ONLY ||
                            (translationMode == TranslationSettingsFragment.TranslationMode.SELECTIVELY &&
                                    message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()));

                    // Установка видимости иконок
                    transcriptionIcon.setVisibility(showTranscriptionIcon ? View.VISIBLE : View.GONE);
                    translateIconAudio.setVisibility(showTranslateIconAudio ? View.VISIBLE : View.GONE);
                    ttsIconAudio.setVisibility(showTTSIconAudio ? View.VISIBLE : View.GONE);
                    ttsTranslatedIconAudio.setVisibility(showTTSTranslatedIconAudio ? View.VISIBLE : View.GONE);

                    Log.d(TAG, "Message id=" + message.getId() + ": transcriptionIcon=" + (showTranscriptionIcon ? "VISIBLE" : "GONE") +
                            ", translateIconAudio=" + (showTranslateIconAudio ? "VISIBLE" : "GONE") +
                            ", ttsIconAudio=" + (showTTSIconAudio ? "VISIBLE" : "GONE") +
                            ", ttsTranslatedIconAudio=" + (showTTSTranslatedIconAudio ? "VISIBLE" : "GONE"));

                    // Обработчик для иконки транскрипции
                    if (showTranscriptionIcon) {
                        transcriptionIcon.setOnClickListener(v -> {
                            Log.d(TAG, "Transcription icon clicked for message id=" + message.getId());
                            if (context instanceof PrivateChatActivity) {
                                ApiService apiService = ApiClient.getApiService(); // Получаем ApiService
                                PrivateChatTranslationAndSpellCheckHelper.transcribeMessage(
                                        context,
                                        message,
                                        chatId,
                                        MessageAdapter.this,
                                        messages,
                                        apiService,
                                        () -> {
                                            if (position != RecyclerView.NO_POSITION) {
                                                if (message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty()) {
                                                    transcribedContentTextView.setText(message.getTranscribedContent());
                                                    transcribedContentTextView.setVisibility(View.VISIBLE);
                                                    if (isTranslationEnabled && translationMode != TranslationSettingsFragment.TranslationMode.SELECTIVELY) {
                                                        message.setContent(message.getTranscribedContent());
                                                        PrivateChatTranslationAndSpellCheckHelper.translateMessage(
                                                                context,
                                                                message,
                                                                translationLanguage,
                                                                chatId,
                                                                MessageAdapter.this,
                                                                messages,
                                                                new ArrayList<>(),
                                                                () -> {
                                                                    Log.d(TAG, "Translation completed for transcribed message id=" + message.getId());
                                                                    notifyItemChanged(position);
                                                                }
                                                        );
                                                    }
                                                    notifyItemChanged(position);
                                                    Log.d(TAG, "Transcription completed for message id=" + message.getId() + " at position=" + position);
                                                } else {
                                                    transcribedContentTextView.setVisibility(View.GONE);
                                                    Log.w(TAG, "No transcription available for message id=" + message.getId());
                                                }
                                            } else {
                                                Log.w(TAG, "Cannot notify item changed: invalid position for message id=" + message.getId());
                                            }
                                        }
                                );
                            } else {
                                Log.e(TAG, "Context is not PrivateChatActivity for message id=" + message.getId());
                                Toast.makeText(context, "Transcription not available", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } else {
                        transcriptionIcon.setOnClickListener(null);
                    }

                    // Обработчик для иконки перевода
                    if (showTranslateIconAudio) {
                        translateIconAudio.setOnClickListener(v -> {
                            Log.d(TAG, "Translate icon clicked for audio message id=" + message.getId());
                            List<Message> translatedMessages = new ArrayList<>();
                            PrivateChatTranslationAndSpellCheckHelper.translateMessage(
                                    context,
                                    message,
                                    translationLanguage,
                                    chatId,
                                    MessageAdapter.this,
                                    messages,
                                    translatedMessages,
                                    () -> {
                                        if (position != RecyclerView.NO_POSITION) {
                                            notifyItemChanged(position);
                                            Log.d(TAG, "Translation completed for audio message id=" + message.getId() + " at position=" + position);
                                        } else {
                                            Log.w(TAG, "Cannot notify item changed: invalid position for message id=" + message.getId());
                                        }
                                    }
                            );
                        });
                    } else {
                        translateIconAudio.setOnClickListener(null);
                    }

                    // Обработчик для иконки TTS оригинала
                    if (showTTSIconAudio) {
                        ttsIconAudio.setOnClickListener(v -> {
                            if (message.getTranscribedContent() == null || message.getTranscribedContent().isEmpty()) {
                                Log.w(TAG, "No transcribed content for TTS: msgId=" + message.getId());
                                Toast.makeText(context, "No transcribed content for TTS", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            playTTS(message.getTranscribedContent(), "auto", message.getId(), false);
                        });
                    } else {
                        ttsIconAudio.setOnClickListener(null);
                    }

                    // Обработчик для иконки TTS переведенного
                    if (showTTSTranslatedIconAudio) {
                        ttsTranslatedIconAudio.setOnClickListener(v -> {
                            if (message.getTranslatedContent() == null || message.getTranslatedContent().isEmpty()) {
                                Log.w(TAG, "No translated content for TTS: msgId=" + message.getId());
                                Toast.makeText(context, "No translated content available for TTS", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            playTTS(message.getTranslatedContent(), translationLanguage, message.getId(), true);
                        });
                    } else {
                        ttsTranslatedIconAudio.setOnClickListener(null);
                    }
                } else {
                    Log.e(TAG, "One or more audio icons are null for message id=" + message.getId() +
                            ": transcriptionIcon=" + (transcriptionIcon == null ? "null" : "not null") +
                            ", translateIconAudio=" + (translateIconAudio == null ? "null" : "not null") +
                            ", ttsIconAudio=" + (ttsIconAudio == null ? "null" : "not null") +
                            ", ttsTranslatedIconAudio=" + (ttsTranslatedIconAudio == null ? "null" : "not null"));
                } // Конец добавления для иконок в аудиосообщениях

                // Добавлено: Отображение текста транскрипции
                if (transcribedContentTextView != null) {
                    if (isTranscriptionEnabled && message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty()) {
                        transcribedContentTextView.setText(message.getTranscribedContent());
                        transcribedContentTextView.setVisibility(View.VISIBLE);
                        Log.d(TAG, "Message id=" + message.getId() + ": showing transcribed content=" + message.getTranscribedContent());
                    } else {
                        transcribedContentTextView.setVisibility(View.GONE);
                        Log.d(TAG, "Message id=" + message.getId() + ": hiding transcribed content");
                    }
                } else {
                    Log.e(TAG, "transcribedContentTextView is null for message id=" + message.getId());
                }
            } else if (fileUrl != null && !fileUrl.trim().isEmpty() && !fileUrl.equalsIgnoreCase("null")) {

                String lowerFileUrl = fileUrl.toLowerCase();

                if (lowerFileUrl.endsWith(".jpg") || lowerFileUrl.endsWith(".jpeg") ||
                        lowerFileUrl.endsWith(".png") || lowerFileUrl.endsWith(".gif")) {
                    // Изображение — превью
                    fileLinkContainer.setVisibility(View.GONE);
                    audioContainer.setVisibility(View.GONE);
                    // Файл мог пропасть с сервера: вместо красного знака во всю
                    // ширину показываем строку «Image unavailable». Про уже
                    // пропавшие помним — иначе заглушка мелькает при каждом входе.
                    boolean knownMissing = MissingAttachments.isMissing(context, fileUrl);
                    attachmentUnavailable.setText("Image unavailable");
                    imagePreview.setVisibility(knownMissing ? View.GONE : View.VISIBLE);
                    if (imageCard != null) imageCard.setVisibility(knownMissing ? View.GONE : View.VISIBLE);
                    attachmentUnavailable.setVisibility(knownMissing ? View.VISIBLE : View.GONE);

                    if (!knownMissing) {
                        /* Занимаем под снимок его настоящую высоту заранее:
                           иначе строка подрастает в миг загрузки и лента
                           дёргается под пальцем. */
                        ВысотыСнимков.занятьМесто(context, imagePreview, fileUrl);
                        стекло(context)
                                .load(fileUrl)
                                .placeholder(R.drawable.bg_message_image_placeholder)
                                .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                                    @Override
                                    public boolean onLoadFailed(@Nullable com.bumptech.glide.load.engine.GlideException e,
                                                                Object model,
                                                                com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                                boolean isFirstResource) {
                                        MissingAttachments.remember(context, fileUrl);
                                        imagePreview.setVisibility(View.GONE);
                                        if (imageCard != null) imageCard.setVisibility(View.GONE);
                                        attachmentUnavailable.setText("Image unavailable");
                                        attachmentUnavailable.setVisibility(View.VISIBLE);
                                        applyFooterLayout();
                                        return false;
                                    }

                                    @Override
                                    public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                                                   Object model,
                                                                   com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                                   com.bumptech.glide.load.DataSource dataSource,
                                                                   boolean isFirstResource) {
                                        MissingAttachments.forget(context, fileUrl);
                                        ВысотыСнимков.запомнить(context, imagePreview, fileUrl, resource);
                                        attachmentUnavailable.setVisibility(View.GONE);
                                        applyFooterLayout();
                                        return false;
                                    }
                                })
                                .into(imagePreview);
                    }

                    imagePreview.setOnClickListener(v -> {
                        /* Идёт выбор — снимок не открываем, а выделяем. */
                        if (выбор != null && выбор.идёт()) { выбор.переключить(message); return; }

                        Intent intent = new Intent(context, FullScreenMessageImageActivity.class);
                        intent.putExtra("message_image_url", fileUrl);
                        context.startActivity(intent);
                    });

                    Log.d(TAG, "Image preview shown for message id=" + message.getId());
                } else {
                    // Всё остальное (включая любые MP3, PDF и т.д.) — обычная ссылка
                    fileLinkContainer.setVisibility(View.VISIBLE);
                    audioContainer.setVisibility(View.GONE);
                    imagePreview.setVisibility(View.GONE);
                    if (imageCard != null) imageCard.setVisibility(View.GONE);
                    fileLinkContainer.setOnClickListener(v -> downloadFile(fileUrl));

                    // Опционально: красивый текст для аудиофайлов
                    if (lowerFileUrl.endsWith(".mp3") || lowerFileUrl.endsWith(".m4a") ||
                            lowerFileUrl.endsWith(".wav") || lowerFileUrl.endsWith(".ogg") ||
                            lowerFileUrl.endsWith(".aac")) {
                    }
                    Log.d(TAG, "Regular file link shown for message id=" + message.getId());
                }
            } else {
                fileLinkContainer.setVisibility(View.GONE);
                audioContainer.setVisibility(View.GONE);
                imagePreview.setVisibility(View.GONE);
                if (imageCard != null) imageCard.setVisibility(View.GONE);
            }

            // Установка содержимого сообщения с учётом перевода и транскрипции
            if ("audio".equalsIgnoreCase(message.getMessageType())) {
                // Всегда скрываем translatedContentTextView для аудиосообщений
                translatedContentTextView.setVisibility(View.GONE);
                Log.d(TAG, "Message id=" + message.getId() + ": translatedContentTextView forced to GONE (audio)");
                if (isTranscriptionEnabled && message.getTranscribedContent() != null && !message.getTranscribedContent().isEmpty()) {
                    // Для аудиосообщений по умолчанию показываем перевод, если он есть
                    if (message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) {
                        contentTextView.setText(message.isShowOriginal() ? message.getTranscribedContent() : message.getTranslatedContent());
                        Log.d(TAG, "Message id=" + message.getId() + ": showing " + (message.isShowOriginal() ? "transcribed content=" + message.getTranscribedContent() : "translated content=" + message.getTranslatedContent()));
                    } else {
                        contentTextView.setText(message.getTranscribedContent()); // Показываем транскрипцию, если нет перевода
                        Log.d(TAG, "Message id=" + message.getId() + ": showing transcribed content=" + message.getTranscribedContent() + ", no translation available");
                    }
                    contentTextView.setVisibility(View.VISIBLE);
                    // Отображаем транскрипцию под проигрывателем
                    if (transcribedContentTextView != null) {
                        transcribedContentTextView.setText(message.getTranscribedContent());
                        transcribedContentTextView.setVisibility(View.VISIBLE);
                        Log.d(TAG, "Message id=" + message.getId() + ": showing transcribed content under player=" + message.getTranscribedContent());
                    } else {
                        Log.e(TAG, "transcribedContentTextView is null for message id=" + message.getId());
                    }
                } else {
                    contentTextView.setVisibility(View.GONE);
                    if (transcribedContentTextView != null) {
                        transcribedContentTextView.setVisibility(View.GONE);
                    }
                }
            } else {
                // Логика для текстовых и других типов сообщений
                if (isTranslationEnabled && message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty()) {
                    contentTextView.setText(message.isShowOriginal() ? message.getContent() : message.getTranslatedContent());
                    translatedContentTextView.setText(message.getTranslatedContent());
                    translatedContentTextView.setVisibility(View.VISIBLE);
                    Log.d(TAG, "Message id=" + message.getId() + ": showing translated content, showOriginal=" + message.isShowOriginal() + ", translatedContentTextView=VISIBLE");
                } else {
                    contentTextView.setText(TextUtils.isEmpty(message.getContent()) ? "" : message.getContent());
                    translatedContentTextView.setVisibility(View.GONE);
                    Log.d(TAG, "Message id=" + message.getId() + ": no translation or transcription available, translatedContentTextView=GONE");
                }
                contentTextView.setVisibility(View.VISIBLE);
            }

            // Индикаторы доставки и прочтения
            indicatorsContainer.setVisibility(isSentByCurrentUser ? View.VISIBLE : View.GONE);
            if (isSentByCurrentUser) {
                // Восстановление статуса delivered из SharedPreferences
                SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
                long chatId = message.getChat() != null ? message.getChat().getId() : -1;
                boolean savedDelivered = prefs.getBoolean("delivered_" + chatId + "_" + message.getId(), message.isDelivered());
                if (savedDelivered && !message.isDelivered()) {
                    message.setDelivered(true);
                    Log.d(TAG, "Restored delivered status for message id=" + message.getId() + " from SharedPreferences: delivered=true");
                }
                deliveryIndicator.setVisibility(message.isDelivered() ? View.VISIBLE : View.GONE);
                readIndicator.setVisibility(message.isRead() ? View.VISIBLE : View.GONE);

                Log.d(TAG, "Message id=" + message.getId() + ": deliveryIndicator visibility=" +
                        (message.isDelivered() ? "VISIBLE" : "GONE") + ", readIndicator visibility=" +
                        (message.isRead() ? "VISIBLE" : "GONE"));
            }

            // Установка временной метки
            String timestampStr = message.getTimestamp();
            if (!TextUtils.isEmpty(timestampStr)) {
                try {
                    ZonedDateTime kievTime = поКиеву(timestampStr);
                    if (kievTime == null) throw new DateTimeParseException("нет метки", timestampStr, 0);
                    String formattedTime;

                    // Только часы и минуты: дата стоит разделителем над первым
                    // сообщением дня, повторять её в каждом пузыре незачем
                    formattedTime = kievTime.format(ЧАСЫ);

                    timestampTextView.setText(formattedTime);
                    timestampTextView.setVisibility(View.VISIBLE);
                    Log.d(TAG, "Message at position " + position + ": UTC=" + timestampStr + ", Kiev=" + formattedTime);
                } catch (DateTimeParseException e) {
                    Log.w(TAG, "Failed to parse timestamp at position " + position + ": " + timestampStr, e);
                    timestampTextView.setVisibility(View.GONE);
                }
            } else {
                timestampTextView.setVisibility(View.GONE);
                Log.w(TAG, "Message at position " + position + ": timestamp is null or empty");
            }

            // Получение настроек перевода и TTS
            SharedPreferences prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE);
            Log.d(TAG, "Binding message id=" + message.getId() + ", type=" + message.getMessageType() +
                    ", content=" + message.getContent() + ", position=" + position);

            boolean showTranslateIcon = false;
            boolean showTTSIcon = false;
            boolean showTTSTranslatedIcon = false;
            String translationLanguage = "auto";
            TranslationSettingsFragment.TranslationMode translationMode = TranslationSettingsFragment.TranslationMode.ALL;
            long chatId = message.getChat() != null ? message.getChat().getId() : -1;

            boolean isTranslationEnabledLocal = prefs.getBoolean("translation_enabled_" + chatId, false);
            boolean isTTSEnabledLocal = prefs.getBoolean("tts_enabled_" + chatId, false);
            translationLanguage = prefs.getString("translation_language_" + chatId,
                    AppSettings.defaultTranslationLanguage(context));
            String translationModeStr = prefs.getString("translation_mode_" + chatId, "ALL");
            try {
                translationMode = TranslationSettingsFragment.TranslationMode.valueOf(translationModeStr);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid translation mode for message id=" + message.getId() + ": " + translationModeStr + ", defaulting to ALL");
                translationMode = TranslationSettingsFragment.TranslationMode.ALL;
            }

            // Условия для показа иконок
            boolean isTextOrFileWithContent = ("text".equalsIgnoreCase(message.getMessageType()) ||
                    ("file".equalsIgnoreCase(message.getMessageType()) &&
                            message.getContent() != null && !message.getContent().isEmpty())) &&
                    !"audio".equalsIgnoreCase(message.getMessageType()); // Явно исключаем аудиосообщения

            showTranslateIcon = isTranslationEnabledLocal && isTextOrFileWithContent;
            showTTSIcon = isTTSEnabledLocal && isTextOrFileWithContent;
            showTTSTranslatedIcon = isTTSEnabledLocal && isTranslationEnabledLocal &&
                    (translationMode == TranslationSettingsFragment.TranslationMode.ALL ||
                            translationMode == TranslationSettingsFragment.TranslationMode.NEW_ONLY ||
                            (translationMode == TranslationSettingsFragment.TranslationMode.SELECTIVELY &&
                                    message.getTranslatedContent() != null && !message.getTranslatedContent().isEmpty())) &&
                    isTextOrFileWithContent;
            Log.d(TAG, "Message id=" + message.getId() +
                    ": isTranslationEnabledLocal=" + prefs.getBoolean("translation_enabled_" + chatId, false) +
                    ", isTTSEnabledLocal=" + prefs.getBoolean("tts_enabled_" + chatId, false) +
                    ", isTranslationEnabledField=" + isTranslationEnabled +
                    ", isTTSEnabledField=" + isTTSEnabled +
                    ", showTranslateIcon=" + showTranslateIcon +
                    ", showTTSIcon=" + showTTSIcon +
                    ", showTTSTranslatedIcon=" + showTTSTranslatedIcon +
                    ", messageType=" + message.getMessageType() +
                    ", content=" + message.getContent() +
                    ", translatedContent=" + message.getTranslatedContent() +
                    ", isSentByCurrentUser=" + (message.getSender().getId() == currentUserId) +
                    ", translationLanguage=" + translationLanguage +
                    ", translationMode=" + translationMode);

            // Установка видимости и обработчиков для иконки перевода
            if (translateIcon != null) {
                setTranslateIconVisibility(showTranslateIcon ? View.VISIBLE : View.GONE);
                if (showTranslateIcon) {
                    final String finalTranslationLanguage = translationLanguage;
                    translateIcon.setOnClickListener(v -> {
                        Log.d(TAG, "Translate icon clicked for message id=" + message.getId());
                        List<Message> translatedMessages = new ArrayList<>();
                        PrivateChatTranslationAndSpellCheckHelper.translateMessage(
                                context,
                                message,
                                finalTranslationLanguage,
                                message.getChat() != null ? message.getChat().getId() : -1,
                                MessageAdapter.this,
                                messages,
                                translatedMessages,
                                () -> {
                                    if (position != RecyclerView.NO_POSITION) {
                                        notifyItemChanged(position);
                                        Log.d(TAG, "Translation completed for message id=" + message.getId() + " at position=" + position);
                                    } else {
                                        Log.w(TAG, "Cannot notify item changed: invalid position for message id=" + message.getId());
                                    }
                                }
                        );
                    });
                } else {
                    translateIcon.setOnClickListener(null);
                }
            } else {
                Log.e(TAG, "translateIcon is null for message id=" + message.getId());
            }

            // Установка видимости и обработчиков для иконки TTS (оригинальный контент)
            if (ttsIcon != null) {
                ttsIcon.setVisibility(showTTSIcon ? View.VISIBLE : View.GONE);
                if (showTTSIcon) {
                    ttsIcon.setOnClickListener(v -> {
                        if (message.getContent() == null || message.getContent().isEmpty() || "audio".equalsIgnoreCase(message.getMessageType())) {
                            Log.w(TAG, "No valid content for TTS: msgId=" + message.getId());
                            Toast.makeText(context, "No valid content for TTS", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        playTTS(message.getContent(), "auto", message.getId(), false);
                    });
                } else {
                    ttsIcon.setOnClickListener(null);
                }
            } else {
                Log.e(TAG, "ttsIcon is null for message id=" + message.getId());
            }

            // Установка видимости и обработчиков для иконки TTS (переведенный контент)
            if (ttsTranslatedIcon != null) {
                ttsTranslatedIcon.setVisibility(showTTSTranslatedIcon ? View.VISIBLE : View.GONE);
                if (showTTSTranslatedIcon) {
                    ttsTranslatedIcon.setOnClickListener(v -> {
                        if (message.getTranslatedContent() == null || message.getTranslatedContent().isEmpty() ||
                                "audio".equalsIgnoreCase(message.getMessageType())) {
                            Log.w(TAG, "No translated content for TTS: msgId=" + message.getId());
                            Toast.makeText(context, "No translated content available for TTS", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        playTTS(message.getTranslatedContent(), message.getTranslationLanguage() != null ?
                                message.getTranslationLanguage() : "auto", message.getId(), true);
                    });
                } else {
                    ttsTranslatedIcon.setOnClickListener(null);
                }
            } else {
                Log.e(TAG, "ttsTranslatedIcon is null for message id=" + message.getId());
            }

            // ДОБАВЛЕНО: Превью ссылок (только для текстовых сообщений, не аудио/файл)
            if (linkPreviewCard != null) {
                // Всегда скрываем в начале — защита от "прилипания" к чужим сообщениям
                linkPreviewCard.setVisibility(View.GONE);

                String text = message.getContent();
                String messageFileUrl = message.getFileUrl();

                if (!TextUtils.isEmpty(text) &&
                        !"audio".equalsIgnoreCase(message.getMessageType()) &&
                        (messageFileUrl == null || messageFileUrl.trim().isEmpty() || messageFileUrl.equalsIgnoreCase("null"))) {

                    String url = extractFirstUrl(text);
                    if (url != null) {

                        // Разобранное превью помним между входами в чат
                        if (message.getPreviewData() == null) {
                            message.setPreviewData(LinkPreviewCache.get(context, url));
                        }

                        // ──────────────── КЭШ ────────────────
                        if (message.getPreviewData() != null) {
                            Message.LinkPreviewData data = message.getPreviewData();

                            previewTitle.setText(data.title);
                            previewDescription.setText(data.description);
                            previewUrl.setText(android.net.Uri.parse(data.url).getHost());

                            if (!data.imageUrl.isEmpty()) {
                                стекло(context)
                                        .load(data.imageUrl)
                                        .apply(new RequestOptions()
                                                .centerCrop()
                                                .placeholder(android.R.color.darker_gray))
                                        .into(previewImage);
                                previewImage.setVisibility(View.VISIBLE);
                            } else {
                                previewImage.setVisibility(View.GONE);
                            }

                            linkPreviewCard.setVisibility(View.VISIBLE);

                            linkPreviewCard.setOnClickListener(v -> {
                                try {
                                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(data.url));
                                    context.startActivity(browserIntent);
                                } catch (Exception e) {
                                    Toast.makeText(context, "Could not open the link", Toast.LENGTH_SHORT).show();
                                }
                            });

                            makeUrlClickable(url);

                            Log.d(TAG, "Превью из кэша для id=" + message.getId());
                        }
                        // ──────────────── ПАРСИНГ (если кэша нет) ────────────────
                        else {
                            final long msgId = message.getId();
                            final MessageViewHolder currentHolder = this;

                            new Thread(() -> {
                                try {
                                    Message.LinkPreviewData fetched = LinkPreviewCache.fetch(context, url);
                                    if (fetched == null) throw new Exception("превью не получено");

                                    final String fTitle = fetched.title;
                                    final String fDesc = fetched.description;
                                    final String fImageUrl = fetched.imageUrl;

                                    ((Activity) context).runOnUiThread(() -> {
                                        // Самая важная проверка!
                                        if (currentHolder.boundMessageId != msgId) {
                                            Log.w(TAG, "Игнорируем устаревшее превью: holder перепривязан, было msgId=" + msgId + ", сейчас " + currentHolder.boundMessageId);
                                            return;
                                        }

                                        // Сохраняем кэш в объект сообщения
                                        Message.LinkPreviewData data = new Message.LinkPreviewData();
                                        data.title = fTitle;
                                        data.description = fDesc;
                                        data.imageUrl = fImageUrl;
                                        data.url = url;
                                        message.setPreviewData(data);

                                        // Заполняем UI
                                        previewTitle.setText(fTitle);
                                        previewDescription.setText(fDesc);
                                        previewUrl.setText(android.net.Uri.parse(url).getHost());

                                        if (!fImageUrl.isEmpty()) {
                                            стекло(context)
                                                    .load(fImageUrl)
                                                    .apply(new RequestOptions()
                                                            .centerCrop()
                                                            .placeholder(android.R.color.darker_gray))
                                                    .into(previewImage);
                                            previewImage.setVisibility(View.VISIBLE);
                                        } else {
                                            previewImage.setVisibility(View.GONE);
                                        }

                                        linkPreviewCard.setVisibility(View.VISIBLE);

                                        linkPreviewCard.setOnClickListener(v -> {
                                            try {
                                                Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                                                context.startActivity(browserIntent);
                                            } catch (Exception e) {
                                                Toast.makeText(context, "Could not open the link", Toast.LENGTH_SHORT).show();
                                            }
                                        });

                                        makeUrlClickable(url);
                                        applyFooterLayout(); // пузырь стал выше — пересчитываем подвал

                                        Log.d(TAG, "Превью успешно загружено и отображено для msgId=" + msgId);
                                    });
                                } catch (Exception e) {
                                    Log.w(TAG, "Ошибка при парсинге превью для " + url + ": " + e.getMessage());
                                    ((Activity) context).runOnUiThread(() -> {
                                        if (currentHolder.boundMessageId == msgId) {
                                            linkPreviewCard.setVisibility(View.GONE);
                                        }
                                    });
                                }
                            }).start();
                        }
                    }
                }
            }
            // ====================== ЦВЕТ ССЫЛОК ======================
            if (isSentByCurrentUser) {
                // Свои сообщения — красивый синий цвет
                contentTextView.setLinkTextColor(Color.parseColor("#4FC3F7"));
                contentTextView.setHighlightColor(Color.parseColor("#3381D4FA")); // цвет при нажатии
                Log.d(TAG, "Ссылка для своего сообщения установлена в #4FC3F7");
            } else {
                // Чужие сообщения — фиолетовый
                contentTextView.setLinkTextColor(Color.parseColor("#B388FF"));
                contentTextView.setHighlightColor(Color.parseColor("#40BB86FC"));
                Log.d(TAG, "Ссылка для чужого сообщения установлена в #BB86FC");
            }
            // =========================================================
        }

        // Вспомогательные методы для проверки дат

        /**
         * Подпись разделителя дня. Сегодня и вчера называем словами — так
         * принято в телефонных мессенджерах и короче даты.
         */
        private String dayLabel(ZonedDateTime time, ZonedDateTime now) {
            if (isSameDay(time, now)) return "Today";
            if (isYesterday(time, now)) return "Yesterday";
            // Язык системы здесь не годится: «Today» и «Yesterday» рядом
            // по-английски, и дата должна быть на том же языке
            return time.format(time.getYear() == now.getYear() ? ДЕНЬ_ЭТОГО_ГОДА : ДЕНЬ_С_ГОДОМ);
        }

        /** Время сообщения в киевской зоне или null, если метки нет. */
        private ZonedDateTime kievTimeOf(String timestamp) {
            if (TextUtils.isEmpty(timestamp)) return null;
            return поКиеву(timestamp);
        }

        /**
         * Разделитель показываем над первым сообщением дня: сравниваем с
         * предыдущим в списке. У самого первого сообщения он есть всегда.
         */
        private void bindDaySeparator(int position) {
            if (daySeparator == null) return;
            ZonedDateTime mine = kievTimeOf(messages.get(position).getTimestamp());
            if (mine == null) {
                daySeparator.setVisibility(View.GONE);
                return;
            }
            ZonedDateTime previous = position > 0 ? kievTimeOf(messages.get(position - 1).getTimestamp()) : null;
            if (previous != null && isSameDay(previous, mine)) {
                daySeparator.setVisibility(View.GONE);
                return;
            }
            daySeparator.setText(dayLabel(mine, ZonedDateTime.now(КИЕВ)));
            daySeparator.setVisibility(View.VISIBLE);
        }



        /**
         * Время рядом с текстом: подвал лежит накладкой в правом нижнем углу,
         * поэтому последней строке дописывается хвост под его ширину. У
         * пузырей с картинкой, файлом или звуком хвост не помогает — там
         * колонке добавляется запас снизу, и подвал стоит под содержимым.
         */
        /** Длина записи: во время прослушивания на её месте идёт счётчик. */
        private int voiceDurationMs;

        /** Подпись времени у голосового: «0:31» — и длина, и счётчик. */
        private void showVoiceTime(int millis) {
            int sec = Math.max(0, millis) / 1000;
            audioDurationTextView.setText(String.format("%d:%02d", sec / 60, sec % 60));
        }

        private void applyFooterLayout() {
            // Внутри привязки строки ещё переставляются: хвост, посчитанный на
            // полпути, успевал попасть в отрисовку, и пузырь на глазах сужался
            if (binding) return;
            View column = itemView.findViewById(R.id.bubble_column);
            final View footer = itemView.findViewById(R.id.message_footer);
            if (column == null || footer == null) return;

            float density = itemView.getResources().getDisplayMetrics().density;
            int base = (int) (12 * density);
            int footerHeight = (int) (20 * density);

            boolean hasMedia = (imagePreview != null && imagePreview.getVisibility() == View.VISIBLE)
                    || (audioContainer != null && audioContainer.getVisibility() == View.VISIBLE)
                    || (fileLinkContainer != null && fileLinkContainer.getVisibility() == View.VISIBLE)
                    || (linkPreviewCard != null && linkPreviewCard.getVisibility() == View.VISIBLE)
                    || (attachmentUnavailable != null && attachmentUnavailable.getVisibility() == View.VISIBLE);

            final TextView last = lastVisibleText();

            // Хвост держит только последняя строка: забытый у соседней
            // делал пузырь шире, и он потом на глазах сужался
            BubbleFooter.keepTailOnly(last, contentTextView, translatedContentTextView, transcribedContentTextView);

            // Снимок идёт во всю ширину пузыря: поля тут только мешают
            boolean hasPhoto = imagePreview != null && imagePreview.getVisibility() == View.VISIBLE;
            PhotoBubble.flushToEdges(column, hasPhoto, false, base, contentTextView, translatedContentTextView, transcribedContentTextView);
            if (hasMedia || last == null) {
                // Здесь хвост не нужен — подвал встанет под содержимым
                if (last != null) last.setText(BubbleFooter.stripTail(last.getText()));
                BubbleFooter.reserveBelow(column, !BubbleFooter.isEmpty(footer), base, footerHeight);
                return;
            }
            BubbleFooter.reserveBelow(column, false, base, footerHeight);
            // Меряем подвал здесь же: если отложить на следующий кадр, пузырь
            // успевает показаться без хвоста и перестраивается на глазах.
            int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            footer.measure(unspecified, unspecified);
            int footerWidth = footer.getMeasuredWidth();
            if (footerWidth > 0) {
                BubbleFooter.applyTail(last, footerWidth + 12 * density);
            } else {
                footer.post(() -> BubbleFooter.applyTail(last, footer.getWidth() + 12 * density));
            }
        }

        /** Последняя видимая строка пузыря: транскрипция, перевод или сам текст. */
        private TextView lastVisibleText() {
            if (transcribedContentTextView != null
                    && transcribedContentTextView.getVisibility() == View.VISIBLE) {
                return transcribedContentTextView;
            }
            if (translatedContentTextView != null
                    && translatedContentTextView.getVisibility() == View.VISIBLE) {
                return translatedContentTextView;
            }
            if (contentTextView != null && contentTextView.getVisibility() == View.VISIBLE
                    && contentTextView.getText() != null && contentTextView.getText().length() > 0) {
                return contentTextView;
            }
            return null;
        }

        private boolean isSameDay(ZonedDateTime date1, ZonedDateTime date2) {
            return date1.toLocalDate().isEqual(date2.toLocalDate());
        }

        private boolean isYesterday(ZonedDateTime date, ZonedDateTime now) {
            return date.toLocalDate().isEqual(now.toLocalDate().minusDays(1));
        }

        private void makeUrlClickable(String url) {
            String originalText = contentTextView.getText().toString();
            if (TextUtils.isEmpty(originalText) || !originalText.contains(url)) return;

            SpannableString spannable = new SpannableString(originalText);
            int start = originalText.indexOf(url);
            if (start >= 0) {
                int end = start + url.length();

                spannable.setSpan(new android.text.style.UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                // Своё приглашение открываем сами — иначе система уводит
                // человека в браузер, хотя он нажал ссылку в этом же приложении.
                if (InviteLinks.isOurs(url)) {
                    spannable.setSpan(new android.text.style.ClickableSpan() {
                        @Override
                        public void onClick(@NonNull View widget) {
                            InviteLinks.open(widget.getContext(), url);
                        }
                    }, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else {
                    spannable.setSpan(new android.text.style.URLSpan(url), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }

                contentTextView.setText(spannable);
                contentTextView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

                Log.d(TAG, "makeUrlClickable: ссылка сделана кликабельной");
            }
        }
    }

    private void downloadFile(String fileUrl) {
        try {
            // Извлечение имени файла из URL
            String fileName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1);
            String mimeType = determineMimeType(fileName);

            Log.d(TAG, "Downloading file: url=" + fileUrl + ", name=" + fileName + ", mimeType=" + mimeType);

            // Настройка запроса на скачивание
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(fileUrl));
            request.setTitle(fileName);
            request.setDescription("Downloading file");
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setMimeType(mimeType);

            // Запуск скачивания
            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            long downloadId = downloadManager.enqueue(request);

            // Регистрация слушателя завершения скачивания
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        DownloadManager.Query query = new DownloadManager.Query();
                        query.setFilterById(downloadId);
                        Cursor cursor = downloadManager.query(query);
                        if (cursor.moveToFirst()) {
                            int status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
                            String message = status == DownloadManager.STATUS_SUCCESSFUL ? "Download completed" : "Download failed";
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                        }
                        cursor.close();
                        context.unregisterReceiver(this);
                    }
                }
            };
            context.registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

            Toast.makeText(context, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error downloading file: " + e.getMessage(), e);
            Toast.makeText(context, "Error downloading file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String determineMimeType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "application/octet-stream";
        }
        String extension;
        try {
            extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        } catch (Exception e) {
            return "application/octet-stream";
        }
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "pdf":
                return "application/pdf";
            default:
                return "application/octet-stream";
        }
    }

    static class MessageDiffCallback extends DiffUtil.Callback {
        private final List<Message> oldList;
        private final List<Message> newList;

        public MessageDiffCallback(List<Message> oldList, List<Message> newList) {
            this.oldList = oldList != null ? oldList : new ArrayList<>();
            this.newList = newList != null ? new ArrayList<>(newList) : new ArrayList<>();
        }

        @Override
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            Message oldMessage = oldList.get(oldItemPosition);
            Message newMessage = newList.get(newItemPosition);
            return oldMessage != null && newMessage != null && oldMessage.getId() == newMessage.getId();
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            Message oldMessage = oldList.get(oldItemPosition);
            Message newMessage = newList.get(newItemPosition);
            if (oldMessage == null || newMessage == null) {
                return false;
            }
            /* У карточки звонка нет отправителя: она не приходит ни от кого,
               её складывают из записи о звонке. Сравнивать её по отправителю
               нельзя — на этом приложение падало при каждом обновлении ленты. */
            if (oldMessage.getSender() == null || newMessage.getSender() == null) {
                return TextUtils.equals(oldMessage.getContent(), newMessage.getContent())
                        && TextUtils.equals(oldMessage.getTimestamp(), newMessage.getTimestamp());
            }
            return TextUtils.equals(oldMessage.getContent(), newMessage.getContent()) &&
                    oldMessage.isDelivered() == newMessage.isDelivered() &&
                    oldMessage.isRead() == newMessage.isRead() &&
                    TextUtils.equals(oldMessage.getFileUrl(), newMessage.getFileUrl()) &&
                    TextUtils.equals(oldMessage.getTimestamp(), newMessage.getTimestamp()) &&
                    oldMessage.getSender().getId() == newMessage.getSender().getId() &&
                    TextUtils.equals(oldMessage.getSender().getUsername(), newMessage.getSender().getUsername()) &&
                    TextUtils.equals(oldMessage.getSender().getAvatar(), newMessage.getSender().getAvatar());
        }
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
            Log.d(TAG, "MediaPlayer released on detach from RecyclerView");
        }
    }

    // ДОБАВЛЕНО: Превью ссылок — находит первую ссылку в тексте
    /** Отвечает ли сервер, что файла по адресу больше нет. Только из фона. */
    private static boolean isGoneFromServer(String url) {
        try {
            java.net.HttpURLConnection connection =
                    (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            try {
                int code = connection.getResponseCode();
                return code == 404 || code == 410 || code == 500;
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "Не удалось проверить " + url + ": " + e.getMessage());
            return false;
        }
    }

    private String extractFirstUrl(String text) {
        if (TextUtils.isEmpty(text)) return null;

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?i)\\b((?:https?://|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,4}/)(?:[^\\s()<>]+|\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\))+(?:\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))"
        );
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String url = matcher.group();
            if (!url.startsWith("http")) url = "https://" + url;
            return url;
        }
        return null;
    }

    /**
     * Скопировать текст сообщения в буфер обмена.
     *
     * Копирования не было нигде: ни ссылку из сообщения достать, ни кусок
     * текста. А меню по долгому нажатию показывалось только для своих
     * сообщений, поэтому к чужим не было доступа вовсе.
     */
    static void copyMessageText(android.content.Context context, String text) {
        if (text == null || text.isEmpty()) return;
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                context.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Message", text));
        android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show();
    }
}