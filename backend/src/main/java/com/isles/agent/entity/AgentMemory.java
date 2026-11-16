package com.isles.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * AI 代理记忆（表 {@code agent_memories}，V13 / docs/12 §3.3）。
 *
 * <p>{@code fingerprint} 是归一化后的指纹（去标点、小写、压缩空白后取 MD5），
 * 用于「7 天内不重复同一话题」的判定。
 */
@Getter
@Setter
@TableName("agent_memories")
public class AgentMemory {

    @TableId(type = IdType.INPUT)
    private String id;
    private String agentId;
    private String kind;
    private String content;
    private String fingerprint;
    private String sourceRef;
    private LocalDateTime createdAt;

    public enum Kind {
        TOPIC, OPINION, CATCHPHRASE, FACT,
        /** 联网搜索命中过的来源 URL（P1 新增）：用于选题去重与内容追溯，不参与话题指纹比较。 */
        SOURCE
    }
}
