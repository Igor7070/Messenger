package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.DeviceKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceKeyRepository extends JpaRepository<DeviceKey, Long> {

    /** Все устройства человека: собеседник проверяет подпись против этого списка. */
    List<DeviceKey> findByUserId(Long userId);

    Optional<DeviceKey> findByUserIdAndDeviceId(Long userId, String deviceId);

    void deleteByUserId(Long userId);
}
