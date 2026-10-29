package com.example.unlimitedpossibilities12.api;

import java.util.List;
import java.util.Map;

import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Multipart;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Part;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.Path;

public interface ApiService {
    // Чаты
    @GET("chats")
    Call<List<Chat>> getChats();

    @POST("chats")
    Call<Chat> createChat(@Body Chat chat);

    /** Удалить чат целиком (у всех). Групповой сервер отдаёт только создателю. */
    @DELETE("chats/{chatId}")
    Call<Void> deleteChat(@Path("chatId") long chatId);

    @GET("chats/{chatId}")
    Call<Chat> getChatById(@Path("chatId") long chatId);

    // Участники групп и приглашения
    @GET("chats/{chatId}/members")
    Call<List<ChatMember>> getChatMembers(@Path("chatId") long chatId);

    @POST("chats/{chatId}/members/{userId}")
    Call<Void> addChatMember(@Path("chatId") long chatId, @Path("userId") long userId);

    @DELETE("chats/{chatId}/members/{userId}")
    Call<Void> removeChatMember(@Path("chatId") long chatId, @Path("userId") long userId);

    @POST("chats/{chatId}/leave")
    Call<Void> leaveChat(@Path("chatId") long chatId);

    @POST("chats/{chatId}/join")
    Call<Void> joinChat(@Path("chatId") long chatId);

    @PUT("chats/{chatId}/members/{userId}/role")
    Call<Void> setMemberRole(@Path("chatId") long chatId, @Path("userId") long userId,
                             @Body Map<String, String> body);

    @POST("chats/{chatId}/transfer/{userId}")
    Call<Void> transferChatOwnership(@Path("chatId") long chatId, @Path("userId") long userId);

    @POST("chats/{chatId}/invites")
    Call<Map<String, Object>> createChatInvite(@Path("chatId") long chatId,
                                               @Body Map<String, Object> body);

    @PUT("chats/{chatId}")
    Call<Chat> updateChat(@Path("chatId") long chatId, @Body Map<String, Object> body);

    @Multipart
    @POST("chats/{chatId}/avatar")
    Call<Chat> uploadChatAvatar(@Path("chatId") long chatId,
                                @Part okhttp3.MultipartBody.Part avatar);

    /** Карточка группы до вступления: имя, картинка, число участников. */
    @GET("invites/{token}")
    Call<Map<String, Object>> previewInvite(@Path("token") String token);

    @POST("invites/{token}/accept")
    Call<Map<String, Object>> acceptInvite(@Path("token") String token);

    @GET("chats/check")
    Call<Map<String, Object>> checkChatByUsers(@Query("name1") String name1, @Query("name2") String name2);

    @GET("users/{userId}/private-chats/{chatId}/exists")
    Call<ResponseBody> checkChatExists(@Path("userId") long userId, @Path("chatId") long chatId);

    @POST("users/{userId}/private-chats/{chatId}/add")
    Call<Void> addChatToUser(@Path("userId") int userId, @Path("chatId") long chatId);

    @GET("users/{userId}/private-chats")
    Call<List<Chat>> getPrivateChats(@Path("userId") int userId);

    @DELETE("users/{userId}/private-chats/{chatId}")
    Call<Void> deleteChatFromUser(@Path("userId") int userId, @Path("chatId") long chatId);

    @DELETE("users/private-chats/check/{chatId}")
    Call<ResponseBody> checkAndDeleteChat(@Path("chatId") long chatId);

    // Пользователи
    @GET("users")
    Call<List<User>> getUsers();

    @Multipart
    @POST("users/create")
    Call<User> createUser(
            @Part("username") RequestBody username,
            @Part("password") RequestBody password,
            @Part("bio") RequestBody bio,
            @Part MultipartBody.Part avatar
    );

    @Multipart
    @PUT("users/{id}")
    Call<User> updateUser(
            @Path("id") int id,
            @Part("info") RequestBody info,
            @Part MultipartBody.Part avatar
    );

    @POST("users/login")
    Call<User> loginUser(@Body LoginRequest loginRequest);

    @GET("users/{id}")
    Call<User> getUserById(@Path("id") int id);

    // Удаление пользователя
    @DELETE("users/{id}")
    Call<ResponseBody> deleteUser(@Path("id") int id);

    // Звонки
    @POST("webrtc/call")
    Call<Void> initiateCall(@Body CallRequest callRequest);

    // Сообщения
    @GET("messages")
    Call<List<Message>> getMessages(@Query("chatId") long chatId);

    // Метод для получения сообщений по идентификатору чата
    @GET("chats/{chatId}/messages")
    Call<List<Message>> getMessagesByChatId(@Path("chatId") long chatId);

    @GET("messages/{messageId}")
    Call<Message> getMessageById(@Path("messageId") long messageId);

    @GET("messages/search")
    Call<Message> searchMessage(
            @Query("content") String content,
            @Query("senderId") int senderId,
            @Query("chatId") long chatId
    );

    @POST("messages/notify")
    Call<Void> notifyMessage(@Body MessageNotification notification);

    // Файлы
    @Multipart
    @POST("files/upload")
    Call<FileUploadResponse> uploadFile(@Part MultipartBody.Part file);

    @GET("users/by-username/{username}")
    Call<User> getUserByUsername(@Path("username") String username);

    @POST("/api/translate")
    Call<String> translateMessage(@Body MessageRequest request);

    @POST("/api/messages/{messageId}/translate")
    Call<Void> saveTranslation(@Path("messageId") long messageId, @Body TranslationUpdateRequest request);

    @POST("/api/spellcheck")
    Call<String> spellCheckMessage(@Body MessageRequest messageRequest);

    @POST("/api/settings")
    Call<Void> saveSettings(@Body Settings settings);
    @POST("tts")
    Call<ResponseBody> generateTTS(@Body RequestBody requestBody);
    @POST("transcribe/{messageId}")
    Call<String> transcribeAudio(@Path("messageId") Long messageId, @Body Map<String, String> request);

    @GET("calls")
    retrofit2.Call<List<com.example.unlimitedpossibilities12.api.Call>> getCalls(@Query("userId") int userId);

    @DELETE("calls/{callId}")
    retrofit2.Call<Void> deleteCall(@Path("callId") long callId, @Query("userId") int userId);

    @POST("/api/public_keys")
    Call<Void> uploadPublicKey(@Body PublicKeyRequest body);

    @GET("/api/public_keys/{userId}")
    Call<PublicKeyResponse> getPublicKey(@Path("userId") long userId);

    @GET("public_keys/history/{publicKeyId}")
    Call<PublicKeyResponse> getPublicKeyById(@Path("publicKeyId") long publicKeyId);

    @Multipart
    @POST("/api/messages/upload_encrypted_file")
    Call<FileUploadResponse> uploadEncryptedFile(
            @Part MultipartBody.Part file,
            @Part("nonce") RequestBody nonce,
            @Part("messageId") RequestBody messageId
    );

    @GET("/api/messages/file_metadata/{messageId}")
    Call<FileMetadata> getFileMetadata(@Path("messageId") long messageId);

    @POST("/api/generate-message")
    Call<String> generateMessage(@Body GenerateMessageRequest request);

    @Multipart
    @POST("/api/global-assistant/translate-voice")
    Call<Map<String, Object>> translateVoice(
            @Part MultipartBody.Part audio,
            @Query("targetLanguage") String targetLanguage,
            @Query("sourceLanguage") String sourceLanguage,
            @Query("withTts") boolean withTts
    );
}
