package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {
    FileMetadata findByMessageId(Long messageId);

    /** Сведения обо всех вложениях разом — для выдачи истории. */
    java.util.List<FileMetadata> findByMessageIdIn(java.util.List<Long> messageIds);
}
