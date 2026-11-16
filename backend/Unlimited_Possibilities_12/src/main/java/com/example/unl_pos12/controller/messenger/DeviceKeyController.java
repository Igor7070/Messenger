package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.DeviceKey;
import com.example.unl_pos12.repo.DeviceKeyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ключи подписи звонков по устройствам.
 *
 * У человека может быть телефон и браузер, и у каждого свой ключ. Прежний
 * /api/sign_keys хранил один ключ на всю учётную запись, поэтому устройства
 * затирали ключи друг друга, а собеседнику это выглядело как подмена.
 *
 * Здесь список: кто спрашивает — получает все устройства человека и сверяет
 * подпись с ключом того устройства, которое звонит.
 */
@RestController
@RequestMapping("/api/device_keys")
public class DeviceKeyController {

    @Autowired
    private DeviceKeyRepository repository;

    @Autowired
    private com.example.unl_pos12.service.AuthzService authz;

    /** Список устройств человека: метка устройства и его открытый ключ. */
    @GetMapping("/{userId}")
    public ResponseEntity<List<Map<String, Object>>> list(@PathVariable Long userId) {
        List<Map<String, Object>> devices = new ArrayList<>();
        for (DeviceKey key : repository.findByUserId(userId)) {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("deviceId", key.getDeviceId());
            item.put("publicKey", key.getPublicKey());
            item.put("createdAt", key.getCreatedAt());
            devices.add(item);
        }
        return ResponseEntity.ok(devices);
    }

    /** Своё устройство со своим ключом. Чужое записать нельзя. */
    @PostMapping
    public ResponseEntity<String> save(@RequestBody Map<String, String> body,
                                       jakarta.servlet.http.HttpServletRequest http) {
        String userIdText = body.get("userId");
        String deviceId = body.get("deviceId");
        String publicKey = body.get("publicKey");
        if (userIdText == null || deviceId == null || deviceId.isEmpty()
                || publicKey == null || publicKey.isEmpty()) {
            return ResponseEntity.badRequest().body("userId, deviceId and publicKey are required");
        }
        Long userId = Long.valueOf(userIdText);
        authz.requireSelf(http, userId); // ключ устройства можно публиковать только свой

        DeviceKey key = repository.findByUserIdAndDeviceId(userId, deviceId).orElseGet(DeviceKey::new);
        if (key.getId() == null) {
            key.setUserId(userId);
            key.setDeviceId(deviceId);
            key.setCreatedAt(ZonedDateTime.now());
            System.out.println("Device key added for userId " + userId + ", device " + shortId(deviceId));
        } else if (!publicKey.equals(key.getPublicKey())) {
            // Ключ знакомого устройства сменился: у собеседника это вызовет
            // предупреждение, поэтому отметим в журнале, когда и у кого
            System.out.println("Device key CHANGED for userId " + userId + ", device " + shortId(deviceId));
        }
        key.setPublicKey(publicKey);
        key.setUpdatedAt(ZonedDateTime.now());
        repository.save(key);
        return ResponseEntity.ok("saved");
    }

    private static String shortId(String deviceId) {
        return deviceId.length() > 8 ? deviceId.substring(0, 8) : deviceId;
    }
}
