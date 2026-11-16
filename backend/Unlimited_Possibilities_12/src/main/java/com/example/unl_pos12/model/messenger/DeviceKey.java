package com.example.unl_pos12.model.messenger;

import jakarta.persistence.*;

import java.time.ZonedDateTime;

/**
 * Ключ подписи звонков — отдельный для каждого устройства человека.
 *
 * Прежде ключ был один на учётную запись (таблица sign_keys), и телефон с
 * браузером всё время перезаписывали его друг за другом. Собеседник видел это
 * как «ключ сменился» при каждом переходе с телефона в браузер, то есть
 * предупреждение о подмене срабатывало бы на ровном месте и быстро перестало
 * бы что-либо значить.
 *
 * Теперь у каждого устройства свой ключ, а у человека — список устройств.
 * Смена устройства становится обычным делом, а настоящая тревога — когда у
 * знакомого устройства вдруг другой ключ.
 */
@Entity
@Table(name = "device_keys",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "device_id"}))
public class DeviceKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Постоянная метка устройства: заводится при первом запуске и живёт с установкой. */
    @Column(name = "device_id", nullable = false, length = 64)
    private String deviceId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String publicKey;

    @Column(nullable = false)
    private ZonedDateTime createdAt;

    @Column
    private ZonedDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public ZonedDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(ZonedDateTime createdAt) { this.createdAt = createdAt; }
    public ZonedDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(ZonedDateTime updatedAt) { this.updatedAt = updatedAt; }
}
