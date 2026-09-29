package com.project.userservice.model;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.AccessLevel;
import lombok.Builder;

@Entity
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="notification", schema="users",
       indexes = {
        @Index(columnList = "email, session_id", name = "idx_notification") }
       
)
public class Notification extends BaseEntity{

	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private String email;

    @Column(name = "session_id")
    private String sessionId;
    
    private String message;
    
    @Builder.Default
    @Column(name = "is_read")
    private boolean isRead = false;   // read is mysql db preserved word
    
    public Notification(String message, String userId, String sessionId) {
        this.message = message;
        this.sessionId = sessionId;
        this.email = userId;        
    }
}
