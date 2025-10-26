package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.FileMetadataSelf;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FileMetadataSelfRepository extends JpaRepository<FileMetadataSelf, Long> {
    FileMetadataSelf findByMessageId(Long messageId);

    @Modifying
    @Transactional
    @Query("DELETE FROM FileMetadataSelf fms WHERE fms.message.id = :messageId")
    void deleteByMessageId(@Param("messageId") Long messageId);
}
