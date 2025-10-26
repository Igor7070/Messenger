package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.FileMetadataSelf;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileMetadataSelfRepository extends JpaRepository<FileMetadataSelf, Long> {
    FileMetadataSelf findByMessageId(Long messageId);
}
