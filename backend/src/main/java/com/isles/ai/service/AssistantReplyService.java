package com.isles.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.ai.config.AiProperties;
import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.entity.AiTaskLog;
import com.isles.ai.entity.ChannelAiSetting;
import com.isles.ai.mapper.AiTaskLogMapper;
import com.isles.ai.mapper.ChannelAiSettingMapper;
import com.isles.ai.provider.AiChatMessage;
import com.isles.ai.provider.AiChatRequest;
import com.isles.ai.provider.AiChatResult;
import com.isles.ai.provider.AiProvider;
import com.isles.comment.dto.CreateCommentRequest;
import com.isles.comment.entity.Comment;
import com.isles.comment.event.CommentRepliedEvent;
import com.isles.comment.mapper.CommentMapper;
import com.isles.comment.service.CommentService;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import com.isles.post.entity.Post;
import com.isles.post.mapper.PostMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 频道助手「被动接话」：有人回复助手的评论时，助手以同一楼中楼继续回复（FR-ASSIST-05 扩展）。
 *
 * <p>约束与自动任务一致：助手总开关 + 「自动评论」开关 + 每日评论限额；同一评论只回一次
 * （ai_task_logs 唯一键幂等）；助手回复自己的评论不会再次触发（防循环）。
 * 模型调用放在独立线程池，不阻塞用户发评论的请求。
 */
@Component
public class AssistantReplyService {

    private static final Logger log = LoggerFactory.getLogger(AssistantReplyService.class);

    /** 任务日志类型（与自动任务的枚举分开，避免被手动试运行触发） */
    public static final String TASK_TYPE = "REPLY_COMMENT";
    /** 被 @ 触发时的任务日志类型 */
    public static final String TASK_TYPE_MENTION = "REPLY_MENTION";
    private static final String AI_MARKER = "（AI 生成）";
    private static final int MAX_REPLY_LENGTH = 300;
    private static final int POST_EXCERPT_LENGTH = 300;
    private static final int COMMENT_EXCERPT_LENGTH = 300;

    private final ChannelAiSettingMapper settingMapper;
    private final AiTaskLogMapper taskLogMapper;
    private final AiModelConfigService modelConfigService;
    private final CommentService commentService;
    private final CommentMapper commentMapper;
    private final PostMapper postMapper;
    private final AiProperties properties;

    private final ThreadPoolTaskExecutor executor;

    public AssistantReplyService(ChannelAiSettingMapper settingMapper,
                                 AiTaskLogMapper taskLogMapper,
                                 AiModelConfigService modelConfigService,
                                 CommentService commentService,
                                 CommentMapper commentMapper,
                                 PostMapper postMapper,
                                 AiProperties properties,
                                 @Qualifier("assistantReplyExecutor") ThreadPoolTaskExecutor executor) {
        this.settingMapper = settingMapper;
        this.taskLogMapper = taskLogMapper;
        this.modelConfigService = modelConfigService;
        this.commentService = commentService;
        this.commentMapper = commentMapper;
        this.postMapper = postMapper;
        this.properties = properties;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCommentReplied(CommentRepliedEvent event) {
        if (!properties.schedulerEnabled()) {
            return;
        }
        executor.execute(() -> {
            try {
                handle(event);
            } catch (RuntimeException ex) {
                // 旁路能力失败不能影响主流程
                log.warn("频道助手自动回复失败 channelId={} commentId={} message={}",
                        event.channelId(), event.commentId(), ex.getMessage());
            }
        });
    }

    /**
     * 有人在帖子/评论里 @ 了助手：同样走异步回复，但与「回复我的评论」分开计幂等键。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMentioned(com.isles.comment.event.AssistantMentionedEvent event) {
        if (!properties.schedulerEnabled()) {
            return;
        }
        executor.execute(() -> {
            try {
                handleMention(event);
            } catch (RuntimeException ex) {
                log.warn("频道助手响应@提及失败 channelId={} postId={} message={}",
                        event.channelId(), event.postId(), ex.getMessage());
            }
        });
    }

    private void handleMention(com.isles.comment.event.AssistantMentionedEvent event) {
        ChannelAiSetting setting = settingMapper.selectOne(Wrappers.<ChannelAiSetting>lambdaQuery()
                .eq(ChannelAiSetting::getChannelId, event.channelId()));
        if (setting == null || !Boolean.TRUE.equals(setting.getEnabled())
                || setting.getAssistantUserId() == null) {
            return;
        }
        // 若这条评论本身就是「回复助手评论」，交给 REPLY_COMMENT 处理，
        // 否则「回复 + @」同一条评论会触发两次回复。
        if (event.commentId() != null) {
            Comment incoming = commentMapper.selectById(event.commentId());
            if (incoming != null && incoming.getParentId() != null) {
                Comment parent = commentMapper.selectById(incoming.getParentId());
                if (parent != null && setting.getAssistantUserId().equals(parent.getAuthorId())) {
                    return;
                }
            }
        }
        String key = event.commentId() != null ? event.commentId() : event.postId();
        if (taskLogMapper.selectByKey(event.channelId(), TASK_TYPE_MENTION, key) != null) {
            return;
        }
        LocalDateTime now = ClockTimes.nowUtc();
        if (overDailyLimit(setting, now)) {
            return;
        }
        AiModelConfig model = modelConfigService.resolveDefault();
        if (model == null) {
            return;
        }
        AiTaskLog taskLog = startLog(setting, event.channelId(), TASK_TYPE_MENTION, key, now);
        if (taskLog == null) {
            return;
        }
        try {
            String reply = composeReply(setting, model, event.postId(), event.commentId(), event.commentId());
            if (reply == null || reply.isBlank()) {
                finish(taskLog, AiTaskLog.Status.SKIPPED, "模型未返回内容", null);
                return;
            }
            var created = commentService.createComment(event.postId(),
                    new CreateCommentRequest(reply + AI_MARKER, event.commentId(), event.authorId(),
                            List.of(), List.of()),
                    setting.getAssistantUserId());
            finish(taskLog, AiTaskLog.Status.SUCCESS, "已响应@提及：" + abbreviate(reply, 100), created.id());
        } catch (RuntimeException ex) {
            finish(taskLog, AiTaskLog.Status.FAILED, ex.getMessage(), null);
        }
    }

    private void handle(CommentRepliedEvent event) {
        ChannelAiSetting setting = settingMapper.selectOne(Wrappers.<ChannelAiSetting>lambdaQuery()
                .eq(ChannelAiSetting::getChannelId, event.channelId()));
        if (setting == null || !Boolean.TRUE.equals(setting.getEnabled())
                || !Boolean.TRUE.equals(setting.getAutoReplyEnabled())) {
            return;
        }
        String assistantUserId = setting.getAssistantUserId();
        if (assistantUserId == null) {
            return;
        }
        // 只回应「回复助手评论」的场景；助手自己的回复不再触发（防循环）
        if (!assistantUserId.equals(event.parentAuthorId()) || assistantUserId.equals(event.authorId())) {
            return;
        }
        // 幂等：同一条评论只回一次
        if (taskLogMapper.selectByKey(event.channelId(), TASK_TYPE, event.commentId()) != null) {
            return;
        }
        LocalDateTime now = ClockTimes.nowUtc();
        if (overDailyLimit(setting, now)) {
            return;
        }
        AiModelConfig model = modelConfigService.resolveDefault();
        if (model == null) {
            return;
        }

        AiTaskLog taskLog = startLog(setting, event, now);
        if (taskLog == null) {
            return;
        }
        try {
            String reply = generateReply(setting, model, event);
            if (reply == null || reply.isBlank()) {
                finish(taskLog, AiTaskLog.Status.SKIPPED, "模型未返回内容", null);
                return;
            }
            var created = commentService.createComment(event.postId(),
                    new CreateCommentRequest(reply + AI_MARKER, event.parentCommentId(), event.authorId(),
                            List.of(), List.of()),
                    assistantUserId);
            finish(taskLog, AiTaskLog.Status.SUCCESS, "已回复：" + abbreviate(reply, 120), created.id());
        } catch (RuntimeException ex) {
            finish(taskLog, AiTaskLog.Status.FAILED, ex.getMessage(), null);
        }
    }

    private boolean overDailyLimit(ChannelAiSetting setting, LocalDateTime now) {
        int limit = setting.getAutoCommentPerDay() == null ? 3 : setting.getAutoCommentPerDay();
        if (limit <= 0) {
            return true;
        }
        LocalDateTime dayStart = now.toLocalDate().atStartOfDay();
        long done = taskLogMapper.countSuccessBetween(setting.getChannelId(), TASK_TYPE, dayStart, dayStart.plusDays(1));
        return done >= limit;
    }

    private String generateReply(ChannelAiSetting setting, AiModelConfig model, CommentRepliedEvent event) {
        return composeReply(setting, model, event.postId(), event.parentCommentId(), event.commentId());
    }

    /**
     * 生成回复文本：parentCommentId 为助手自己的评论，incomingCommentId 为触发内容（可为 null，表示帖子正文）。
     */
    private String composeReply(ChannelAiSetting setting, AiModelConfig model, String postId,
                                String parentCommentId, String incomingCommentId) {
        Post post = postMapper.selectById(postId);
        Comment parent = parentCommentId == null ? null : commentMapper.selectById(parentCommentId);
        Comment incoming = incomingCommentId == null ? null : commentMapper.selectById(incomingCommentId);
        String persona = setting.getPersona() == null || setting.getPersona().isBlank()
                ? "友善、简洁、乐于帮助岛民"
                : setting.getPersona();
        String systemPrompt = "你是频道「" + safe(setting.getNickname()) + "」的 AI 助手，人设：" + persona
                + "。请用中文回复，不超过 80 字，语气自然，不要复述对方原话，不要使用话题标签，不要编造事实。";
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("帖子标题：").append(post == null ? "" : safe(post.getTitle())).append('\n');
        userPrompt.append("帖子摘要：").append(abbreviate(post == null ? "" : safe(post.getContent()), POST_EXCERPT_LENGTH))
                .append('\n');
        userPrompt.append("你的上一条评论：").append(abbreviate(parent == null ? "" : safe(parent.getContent()),
                COMMENT_EXCERPT_LENGTH)).append('\n');
        userPrompt.append("岛民回复你：").append(abbreviate(incoming == null ? "" : safe(incoming.getContent()),
                COMMENT_EXCERPT_LENGTH)).append('\n');
        userPrompt.append("请直接给出你的回复内容。");

        AiProvider provider = modelConfigService.providerFor(model);
        AiChatResult result = provider.chat(AiChatRequest.of(model.getModel(),
                List.of(AiChatMessage.system(systemPrompt), AiChatMessage.user(userPrompt.toString())),
                false, false, model.getMaxTokens() == null ? 512 : model.getMaxTokens(),
                Boolean.TRUE.equals(model.getReasoningEnabled()), model.getThinkingMode()));
        String content = result == null || result.content() == null ? "" : result.content().trim();
        return content.isBlank() ? null : abbreviate(content, MAX_REPLY_LENGTH);
    }

    private AiTaskLog startLog(ChannelAiSetting setting, CommentRepliedEvent event, LocalDateTime now) {
        return startLog(setting, event.channelId(), TASK_TYPE, event.commentId(), now);
    }

    private AiTaskLog startLog(ChannelAiSetting setting, String channelId, String taskType, String frequencyKey,
                               LocalDateTime now) {
        AiTaskLog taskLog = new AiTaskLog();
        taskLog.setId(UlidGenerator.generate());
        taskLog.setChannelId(channelId);
        taskLog.setSettingId(setting.getId());
        taskLog.setTaskType(taskType);
        taskLog.setFrequencyKey(frequencyKey);
        taskLog.setStatus(AiTaskLog.Status.RUNNING.name());
        taskLog.setStartedAt(now);
        taskLog.setCreatedAt(now);
        try {
            taskLogMapper.insert(taskLog);
            return taskLog;
        } catch (RuntimeException ex) {
            // 并发/重复事件触发唯一键：放弃本次
            return null;
        }
    }

    private void finish(AiTaskLog taskLog, AiTaskLog.Status status, String text, String commentId) {
        taskLog.setStatus(status.name());
        taskLog.setResultText(abbreviate(text, 500));
        taskLog.setCommentId(commentId);
        taskLog.setFinishedAt(ClockTimes.nowUtc());
        taskLogMapper.updateById(taskLog);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
