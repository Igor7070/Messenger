package com.example.unl_pos12.controller.messenger;

import com.example.unl_pos12.model.messenger.Message;
import com.example.unl_pos12.service.MessageService;
import org.springframework.beans.factory.annotation.Autowired;
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
@RequestMapping({"/uploads", "/api/files"}) // Поддержка обоих путей...
public class FileDownloadController {

    @Autowired // ДОБАВЛЕНО
    private MessageService messageService; // Для доступа к messageType

    @GetMapping({"/{filename:.+}", "/download/{filename:.+}"}) // Поддержка /uploads/photo.jpg и /api/files/download/photo.jpg
    public ResponseEntity<Resource> downloadFile(@PathVariable String filename) {
        try {
            // Декодируем имя файла
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8.toString());
            Path filePath = Paths.get("uploads").resolve(decodedFilename).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists() && resource.isReadable()) {
                // Проверяем messageType и contentType для файла
                String mimeType = Files.probeContentType(filePath);
                String fileUrl = "https://unlimitedpossibilities12.org/api/files/download/" + decodedFilename;
                Message message = messageService.findByFileUrl(fileUrl);
                if (message != null && message.getContentType() != null) {
                    mimeType = message.getContentType();
                    System.out.println("Serving file: " + decodedFilename + " with Content-Type: " + mimeType + " (messageType=" + message.getMessageType() + ")");
                } else if (decodedFilename.endsWith(".webm")) {
                    mimeType = "audio/webm;codecs=opus"; // Fallback для .webm
                    System.err.println("Warning: Serving .webm file: " + decodedFilename + " as audio/webm;codecs=opus (messageType=" + (message != null ? message.getMessageType() : "not found") + ")");
                } else if (mimeType == null) {
                    mimeType = determineMimeType(decodedFilename);
                }

                // Проверка Content-Length
                long contentLength = resource.contentLength();
                System.out.println("Serving file: " + decodedFilename + ", Content-Length: " + contentLength);
                if (contentLength <= 0) {
                    System.err.println("Warning: Content-Length is 0 or negative for file: " + decodedFilename);
                }

                // Устанавливаем заголовки
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(mimeType))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CONNECTION, "keep-alive")
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

    @GetMapping("/download/encrypted/{filename:.+}") // Для секретных чатов: /api/files/download/encrypted/<filename>
    public ResponseEntity<Resource> downloadEncryptedFile(@PathVariable String filename) {
        try {
            // Декодируем имя файла
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8.toString());
            Path filePath = Paths.get("uploads/encrypted").resolve(decodedFilename).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists() && resource.isReadable()) {
                // Проверяем messageType и contentType для файла
                String mimeType = "application/octet-stream"; // Зашифрованные файлы как octet-stream
                String fileUrl = "https://unlimitedpossibilities12.org/api/files/download/encrypted/" + decodedFilename;
                Message message = messageService.findByFileUrl(fileUrl);
                if (message != null && message.getContentType() != null) {
                    mimeType = message.getContentType();
                    System.out.println("Serving encrypted file: " + decodedFilename + " with Content-Type: " + mimeType + " (messageType=" + message.getMessageType() + ")");
                }

                // Проверка Content-Length
                long contentLength = resource.contentLength();
                System.out.println("Serving encrypted file: " + decodedFilename + ", Content-Length: " + contentLength);
                if (contentLength <= 0) {
                    System.err.println("Warning: Content-Length is 0 or negative for encrypted file: " + decodedFilename);
                }

                // Устанавливаем заголовки
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(mimeType))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CONNECTION, "keep-alive")
                        .body(resource);
            } else {
                System.out.println("Encrypted file not found: " + decodedFilename);
                return ResponseEntity.status(404).body(null);
            }
        } catch (Exception e) {
            System.err.println("Error downloading encrypted file: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(null);
        }
    }

    @GetMapping("/download/encrypted_self/{filename:.+}")
    public ResponseEntity<Resource> downloadEncryptedSelfFile(@PathVariable String filename) {
        try {
            String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8.toString());
            Path filePath = Paths.get("uploads/encrypted_self").resolve(decodedFilename).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (resource.exists() && resource.isReadable()) {
                String mimeType = "application/octet-stream";
                String fileUrl = "https://unlimitedpossibilities12.org/api/files/download/encrypted_self/" + decodedFilename;
                Message message = messageService.findByFileUrl(fileUrl); // Предполагается, что fileUrlSelf хранится в базе
                if (message != null && message.getContentType() != null) {
                    mimeType = message.getContentType();
                    System.out.println("Serving encrypted_self file: " + decodedFilename + " with Content-Type: " + mimeType + " (messageType=" + message.getMessageType() + ")");
                }

                long contentLength = resource.contentLength();
                System.out.println("Serving encrypted_self file: " + decodedFilename + ", Content-Length: " + contentLength);
                if (contentLength <= 0) {
                    System.err.println("Warning: Content-Length is 0 or negative for encrypted_self file: " + decodedFilename);
                }

                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(mimeType))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .header(HttpHeaders.CONNECTION, "keep-alive")
                        .body(resource);
            } else {
                System.out.println("Encrypted self file not found: " + decodedFilename);
                return ResponseEntity.status(404).body(null);
            }
        } catch (Exception e) {
            System.err.println("Error downloading encrypted_self file: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(null);
        }
    }

    private String determineMimeType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        switch (extension) {
            // Изображения
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "webp":
                return "image/webp";
            case "tiff":
            case "tif":
                return "image/tiff";
            case "svg":
                return "image/svg+xml";
            case "ico":
                return "image/x-icon";

            // Видео
            case "mp4":
                return "video/mp4";
            case "mpeg":
            case "mpg":
                return "video/mpeg";
            case "mov":
                return "video/quicktime";
            case "avi":
                return "video/x-msvideo";
            case "wmv":
                return "video/x-ms-wmv";
            case "flv":
                return "video/x-flv";
            case "webm":
                return "video/webm";
            case "mkv":
                return "video/x-matroska";
            case "3gp":
                return "video/3gpp";

            // Аудио
            case "mp3":
                return "audio/mpeg";
            case "wav":
                return "audio/wav";
            case "ogg":
                return "audio/ogg";
            case "aac":
                return "audio/aac";
            case "flac":
                return "audio/flac";
            case "m4a":
                return "audio/mp4";
            case "wma":
                return "audio/x-ms-wma";

            // Документы
            case "pdf":
                return "application/pdf";
            case "doc":
                return "application/msword";
            case "docx":
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls":
                return "application/vnd.ms-excel";
            case "xlsx":
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt":
                return "application/vnd.ms-powerpoint";
            case "pptx":
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "odt":
                return "application/vnd.oasis.opendocument.text";
            case "ods":
                return "application/vnd.oasis.opendocument.spreadsheet";
            case "odp":
                return "application/vnd.oasis.opendocument.presentation";
            case "rtf":
                return "application/rtf";

            // Архивы
            case "zip":
                return "application/zip";
            case "rar":
                return "application/x-rar-compressed";
            case "7z":
                return "application/x-7z-compressed";
            case "tar":
                return "application/x-tar";
            case "gz":
                return "application/gzip";
            case "bz2":
                return "application/x-bzip2";

            // Текст и код
            case "txt":
                return "text/plain";
            case "csv":
                return "text/csv";
            case "html":
            case "htm":
                return "text/html";
            case "css":
                return "text/css";
            case "js":
                return "application/javascript";
            case "json":
                return "application/json";
            case "xml":
                return "application/xml";
            case "md":
                return "text/markdown";
            case "py":
                return "text/x-python";
            case "java":
                return "text/x-java-source";
            case "c":
                return "text/x-c";
            case "cpp":
                return "text/x-c++src";
            case "sh":
                return "application/x-sh";

            // Другие
            case "epub":
                return "application/epub+zip";
            case "mobi":
                return "application/x-mobipocket-ebook";
            case "ttf":
                return "font/ttf";
            case "woff":
                return "font/woff";
            case "woff2":
                return "font/woff2";
            case "exe":
                return "application/x-msdownload";
            case "dmg":
                return "application/x-apple-diskimage";
            case "iso":
                return "application/x-iso9660-image";

            default:
                return "application/octet-stream";
        }
    }
}
