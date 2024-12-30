package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;

@RestController
@RequestMapping("/api/files")
public class FileDownloadController {
    @Autowired
    private MessageService messageService;

    @GetMapping("/download/{filename:.+}")
    public ResponseEntity<FileSystemResource> downloadFile(@PathVariable String filename) {
        File file = new File("uploads/" + filename); // Убедитесь, что путь правильный
        if (!file.exists()) {
            System.out.println("File not found: " + file.getAbsolutePath());
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "attachment; filename=" + file.getName());

        return ResponseEntity.ok()
                .headers(headers)
                .body(new FileSystemResource(file));
    }
}
