package com.example.unl_pos12.repo;

import com.example.unl_pos12.model.messenger.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    List<User> findByOnline(boolean online);
}
