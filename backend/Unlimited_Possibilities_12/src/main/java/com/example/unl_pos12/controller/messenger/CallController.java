package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Call;
import com.example.unl_pos12.service.CallService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/calls")
public class CallController {
    private final CallService callService;

    @Autowired
    public CallController(CallService callService) {
        this.callService = callService;
    }

    // Создать звонок
    @PostMapping
    public ResponseEntity<Call> createCall(@RequestBody Call call, @RequestParam Long userId) {
        Call createdCall = callService.createCall(call, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdCall);
    }

    // Получить звонки пользователя
    @GetMapping
    public ResponseEntity<List<Call>> getUserCalls(@RequestParam Long userId) {
        List<Call> calls = callService.getUserCalls(userId);
        return ResponseEntity.ok(calls);
    }

    // Получить звонок по ID
    @GetMapping("/{id}")
    public ResponseEntity<Call> getCallById(@PathVariable Long id) {
        Call call = callService.getCallById(id);
        return ResponseEntity.ok(call);
    }

    // Удалить звонок
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteCall(@PathVariable Long id, @RequestParam Long userId) {
        callService.deleteCall(id, userId);
        return ResponseEntity.ok(Map.of("message", "Call deleted"));
    }
}
