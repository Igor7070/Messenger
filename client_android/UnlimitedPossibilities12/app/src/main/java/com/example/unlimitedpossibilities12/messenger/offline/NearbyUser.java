package com.example.unlimitedpossibilities12.messenger.offline;

import android.net.Uri;

/**
 * Класс представляет найденное устройство (пользователя) в оффлайн-режиме
 */
public class NearbyUser {

    private final String endpointId;
    private final String name;
    private Uri avatarUri;

    /**
     * Основной конструктор
     */
    public NearbyUser(String endpointId, String name) {
        this.endpointId = endpointId;
        this.name = name;
        this.avatarUri = null;
    }

    /**
     * Конструктор с аватаром
     */
    public NearbyUser(String endpointId, String name, Uri avatarUri) {
        this.endpointId = endpointId;
        this.name = name;
        this.avatarUri = avatarUri;
    }

    // ====================== GETTERS ======================

    public String getEndpointId() {
        return endpointId;
    }

    public String getName() {
        return name;
    }

    public Uri getAvatarUri() {
        return avatarUri;
    }

    // ====================== SETTERS ======================

    public void setAvatarUri(Uri avatarUri) {
        this.avatarUri = avatarUri;
    }

    // ====================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ======================

    /**
     * Проверяет, есть ли аватар у пользователя
     */
    public boolean hasAvatar() {
        return avatarUri != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NearbyUser that = (NearbyUser) o;
        return endpointId.equals(that.endpointId);
    }

    @Override
    public int hashCode() {
        return endpointId.hashCode();
    }

    @Override
    public String toString() {
        return "NearbyUser{" +
                "endpointId='" + endpointId + '\'' +
                ", name='" + name + '\'' +
                ", hasAvatar=" + hasAvatar() +
                '}';
    }
}
