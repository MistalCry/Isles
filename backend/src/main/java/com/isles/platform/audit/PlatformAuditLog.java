package com.isles.platform.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 平台操作审计（表 {@code platform_audit_logs}，docs/11 §4.1）。
 *
 * <p>只追加：不提供更新/删除接口，Mapper 也不暴露对应方法（docs/07 §9 敏感操作留痕）。
 */
@Getter
@Setter
@TableName("platform_audit_logs")
public class PlatformAuditLog {

    @TableId(type = IdType.INPUT)
    private String id;
    private String actorId;
    /** 操作人昵称快照：用户改名或注销后仍可追溯。 */
    private String actorName;
    /** 动作，形如 {@code platform.config.update}。 */
    private String action;
    private String targetType;
    private String targetId;
    /** JSON：变更前后、原因等。 */
    private String detail;
    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;
}
