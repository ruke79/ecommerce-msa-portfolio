package com.project.common.dto.notification;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.project.common.util.LocalDateTimeUtil;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AccessLevel;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlarmResponse {
    private Long id;
    private String type;
    private String content;
    private String url;
    private Integer[] publishedAt;
    private Long senderId;

    @JsonProperty("checked")
    private boolean read;
    @JsonProperty("del")
    private boolean del;

    @Builder
    public AlarmResponse(Long id, String type, String content, String url, LocalDateTime publishedAt, Long senderId, boolean read, boolean del) {
        this.id = id;
        this.type = type;
        this.content = content;
        this.url = url;
        this.publishedAt = LocalDateTimeUtil.toArray(publishedAt);
        this.senderId = senderId;
        this.read = read;
        this.del = del;
    }
    
}
