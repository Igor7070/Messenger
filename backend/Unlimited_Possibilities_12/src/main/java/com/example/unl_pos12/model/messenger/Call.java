package com.example.unl_pos12.model.messenger;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "Calls")
public class Call {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user; // Пользователь, связанный со звонком

    @Column(nullable = false)
    private String caller; // Номер или ID звонящего

    private String receiver; // Номер или ID получателя (для не-групповых звонков)

    @ElementCollection
    @CollectionTable(name = "call_participants", joinColumns = @JoinColumn(name = "call_id"))
    @Column(name = "participant")
    private List<String> participants = new ArrayList<>(); // Участники для групповых звонков

    @Column(nullable = false)
    private String direction; // "incoming", "outgoing"

    @Column(nullable = false)
    private String status; // "completed", "rejected", "missed"

    @Column(name = "call_type", nullable = false)
    private String callType; // "voice", "video", "translated", "group"

    @Column(nullable = false)
    private LocalDateTime timestamp; // Время начала звонка

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getCaller() {
        return caller;
    }

    public void setCaller(String caller) {
        this.caller = caller;
    }

    public String getReceiver() {
        return receiver;
    }

    public void setReceiver(String receiver) {
        this.receiver = receiver;
    }

    public List<String> getParticipants() {
        return participants;
    }

    public void setParticipants(List<String> participants) {
        this.participants = participants;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String direction) {
        this.direction = direction;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getCallType() {
        return callType;
    }

    public void setCallType(String callType) {
        this.callType = callType;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
