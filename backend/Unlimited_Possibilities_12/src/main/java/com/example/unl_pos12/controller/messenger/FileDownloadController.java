package com.example.unl_pos12.controller.messenger;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping({"/uploads", "/api/files"}) // Поддержка обоих путей
public class FileDownloadController {

    @GetMapping({"/{filename:.+}", "/download/{filename:.+}"}) // Поддержка /uploads/photo.jpg и /api/files/download/photo.jpg
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) {
        try {
            // Декодируем имя файла
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8.toString());
            Path filePath = Paths.get("uploads").resolve(decodedFilename).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists() && resource.isReadable()) {
                // Определяем MIME-тип
                String mimeType = Files.probeContentType(filePath);
                if (mimeType == null) {
                    mimeType = determineMimeType(decodedFilename);
                }

                // Устанавливаем заголовки
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(mimeType))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                        .body(resource);
            } else {
                System.out.println("File not found: " + decodedFilename);
                return ResponseEntity.status(404).body(null);
            }
        } catch (Exception e) {
            System.err.println("Error downloading file: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(null);
        }
    }

    private String determineMimeType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "pdf":
                return "application/pdf";
            default:
                return "application/octet-stream";
        }
    }
}
