package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {
    FileMetadata findByMessageId(Long messageId);
}
