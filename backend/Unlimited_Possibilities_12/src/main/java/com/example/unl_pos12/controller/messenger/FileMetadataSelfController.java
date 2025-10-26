package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.FileMetadataSelf;
import com.example.unl_pos12.model.messenger.FileUploadResponse;
import com.example.unl_pos12.service.FileMetadataSelfService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/messages")
public class FileMetadataSelfController {
    @Autowired
    private FileMetadataSelfService fileMetadataSelfService;

    @PostMapping("/upload_encrypted_file_self")
    public ResponseEntity<FileUploadResponse> uploadEncryptedFileSelf(
            @RequestParam("file") MultipartFile fileSelf,
            @RequestParam("fileName") String fileName,
            @RequestParam("nonce") String nonce,
            @RequestParam("messageId") Long messageId) {
        try {
            FileMetadataSelf fileMetadataSelf = fileMetadataSelfService.saveEncryptedFileSelf(messageId, fileSelf, fileName, nonce);
            return ResponseEntity.ok(new FileUploadResponse(fileMetadataSelf.getFileUrlSelf(), null));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(new FileUploadResponse(null, "Error uploading file for self: " + e.getMessage()));
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
