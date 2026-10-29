package com.example.unlimitedpossibilities12.api;

import android.os.Parcel;
import android.os.Parcelable;

import com.google.gson.annotations.SerializedName;

public class User implements Parcelable {
    private int id;
    private String username;
    private String password;
    @SerializedName("info") // Исправляем на имя поля из JSON
    private String bio;
    private String avatar;
    private boolean online;
    private String token; // токен доступа к API, приходит при логине/регистрации

    public User() {}

    public User(String username, String password, String bio) {
        this.username = username;
        this.password = password;
        this.bio = bio;
    }

    public User(int id, String username, String avatar) {
        this.id = id;
        this.username = username;
        this.avatar = avatar;
    }

    // Добавлено: Конструктор для Parcelable
    protected User(Parcel in) {
        id = in.readInt();
        username = in.readString();
        password = in.readString();
        bio = in.readString();
        avatar = in.readString();
        online = in.readByte() != 0;
    }

    // Добавлено: Реализация Parcelable
    public static final Creator<User> CREATOR = new Creator<User>() {
        @Override
        public User createFromParcel(Parcel in) {
            return new User(in);
        }

        @Override
        public User[] newArray(int size) {
            return new User[size];
        }
    };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(id);
        dest.writeString(username);
        dest.writeString(password);
        dest.writeString(bio);
        dest.writeString(avatar);
        dest.writeByte((byte) (online ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
