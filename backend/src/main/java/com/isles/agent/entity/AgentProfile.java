package com.isles.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 代理人设（表 {@code agent_profiles}，V13 / docs/12 §3.2）。
 *
 * <p>JSON 列（interests / activeHours / channels / toolsEnabled）按字符串存放，
 * 由服务层解析，避免为「可配的少量结构化数据」引入 TypeHandler。
 */
@Getter
@Setter
@TableName("agent_profiles")
public class AgentProfile {

    @TableId(type = IdType.INPUT)
    private String id;
    private String userId;
    private String displayName;
    private String bio;
    private String interests;
    private String tone;
    private String activeHours;
    private Integer postsPerDay;
    private Integer commentsPerDay;
    private String channels;
    private String toolsEnabled;
    private String status;
    private LocalDateTime lastRunAt;

    public enum Status {
        ACTIVE, PAUSED
    }
}
