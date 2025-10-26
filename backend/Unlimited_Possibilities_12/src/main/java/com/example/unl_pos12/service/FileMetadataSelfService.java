package com.example.unl_pos12.service;

import com.example.unl_pos12.model.messenger.FileMetadataSelf;
import com.example.unl_pos12.repo.FileMetadataSelfRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class FileMetadataSelfService {
    @Autowired
    private FileMetadataSelfRepository fileMetadataSelfRepository;

    public FileMetadataSelf getFileMetadataSelfByMessageId(Long messageId) {
        return fileMetadataSelfRepository.findByMessageId(messageId);
    }
}
