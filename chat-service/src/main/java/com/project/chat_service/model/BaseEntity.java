package com.project.chat_service.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import  jakarta.persistence.Column;
import  jakarta.persistence.EntityListeners;
import  jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

@Getter
@MappedSuperclass
@SuperBuilder // @Builder 대신 @SuperBuilder 사용
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class) //entity 이벤트 발생시 처리. 시간에 대한 값을 자동으로 입력
public abstract class BaseEntity {

    @Version
    @Column(name = "version")
    private Long version;

    @CreatedDate//생성일시 자동입력
    @Column(name = "reg_date", updatable = false)
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime regDate;

    @LastModifiedDate
    @Column(name = "mod_date")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime modDate;
}
