package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.FileMetadataSelf;
import com.example.unl_pos12.model.messenger.FileUploadResponse;
import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.service.FileMetadataSelfService;
import com.example.unl_pos12.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/messages")
public class FileMetadataSelfController {

    @Autowired
    private FileMetadataSelfService fileMetadataSelfService;

    @Autowired
    private MessageService messageService; // [ADD] Внедряем MessageService

    // [CHANGE] Обновляем метод uploadEncryptedFileSelf
    @PostMapping("/upload_encrypted_file_self")
    public ResponseEntity<FileUploadResponse> uploadEncryptedFileSelf(
            @RequestParam("file") MultipartFile fileSelf,
            @RequestParam("fileName") String fileName,
            @RequestParam("nonce") String nonce,
            @RequestParam("messageId") Long messageId) {
        try {
            Message message = messageService.getMessageById(messageId);
            if (message == null) {
                return ResponseEntity.badRequest().body(new FileUploadResponse(null, "Message not found for id: " + messageId));
            }
            message = messageService.saveEncryptedFileSelfMessage(message, fileSelf, nonce, fileName);
            Map<String, Object> fileMetadata = messageService.getExtendedFileMetadataByMessageId(messageId);
            if (fileMetadata == null || fileMetadata.get("fileUrlSelf") == null) {
                return ResponseEntity.status(500).body(new FileUploadResponse(null, "Failed to save encrypted self file metadata"));
            }
            return ResponseEntity.ok(new FileUploadResponse((String) fileMetadata.get("fileUrlSelf"), null));
        }catch (IncorrectResultSizeDataAccessException e) {
            return ResponseEntity.status(500).body(new FileUploadResponse(null, "Error uploading file for self: Duplicate records found for messageId=" + messageId));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(new FileUploadResponse(null, "Error uploading file for self: " + e.getMessage()));
        }
    }

    @GetMapping("/file_metadata_self/{messageId}")
    public ResponseEntity<FileMetadataSelf> getFileMetadataSelf(@PathVariable Long messageId) {
        FileMetadataSelf fileMetadataSelf = fileMetadataSelfService.getFileMetadataSelfByMessageId(messageId);
        if (fileMetadataSelf != null) {
            return ResponseEntity.ok(fileMetadataSelf);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
