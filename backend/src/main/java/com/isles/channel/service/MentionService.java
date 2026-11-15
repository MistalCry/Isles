package com.isles.channel.service;

import com.isles.ai.entity.ChannelAiSetting;
import com.isles.ai.mapper.ChannelAiSettingMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.row.MentionCandidateRow;
import com.isles.comment.event.AssistantMentionedEvent;
import com.isles.notification.entity.Notification;
import com.isles.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @提及（FR-POST 扩展）：解析正文里的 {@code @昵称}，给被提及的频道成员发通知；
 * 若提及的是频道助手，则额外触发助手自动回复。
 *
 * <p>只匹配「当前频道成员」，避免通过 @ 探测非成员昵称；同一内容里重复 @ 同一人只通知一次。
 * 解析失败不影响主流程（发帖/评论本身不应因为提及逻辑失败而回滚）。
 */
@Service
@RequiredArgsConstructor
public class MentionService {

    private static final Logger log = LoggerFactory.getLogger(MentionService.class);

    /** ID 化提及：@[ULID:昵称]，前端插入时生成，重名也能精确命中 */
    private static final Pattern MENTION_ID_PATTERN = Pattern.compile("@\\[([A-Za-z0-9]{20,32}):([^\\]\\s]{1,32})\\]");
    /** 兼容手打的 @昵称：支持中英文、数字、下划线、点与短横线 */
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_\\u4e00-\\u9fa5.\\-]{1,32})");
    private static final int CANDIDATE_LIMIT = 200;
    private static final int SNIPPET_LENGTH = 80;

    private final ChannelMemberMapper channelMemberMapper;
    private final ChannelAiSettingMapper settingMapper;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 解析并通知。由发帖/发评论在事务内调用。
     *
     * @param commentId 评论 id（帖子正文提及传 null）
     */
    public void notifyMentions(String channelId, String postId, String commentId, String content, String actorId) {
        if (channelId == null || postId == null || content == null || content.isBlank()) {
            return;
        }
        Set<String> ids = parseMentionIds(content);
        Set<String> names = parseNames(content);
        if (ids.isEmpty() && names.isEmpty()) {
            return;
        }
        try {
            List<MentionCandidateRow> candidates = channelMemberMapper.selectMentionCandidates(channelId, null,
                    CANDIDATE_LIMIT);
            if (candidates == null || candidates.isEmpty()) {
                return;
            }
            String assistantUserId = assistantUserId(channelId);
            Set<String> notified = new LinkedHashSet<>();
            String snippet = snippet(content);
            // 1) ID 化提及：精确命中，且只认本频道成员
            for (MentionCandidateRow candidate : candidates) {
                if (!ids.contains(candidate.getId()) || candidate.getId().equals(actorId)) {
                    continue;
                }
                notifyMention(candidate, channelId, postId, commentId, actorId, snippet, assistantUserId, notified);
            }
            // 2) 兼容手打 @昵称：命中所有同名成员（重名时不再只通知第一个）
            for (String name : names) {
                for (MentionCandidateRow candidate : candidates) {
                    if (!matches(candidate, name) || candidate.getId().equals(actorId)) {
                        continue;
                    }
                    notifyMention(candidate, channelId, postId, commentId, actorId, snippet, assistantUserId, notified);
                }
            }
        } catch (RuntimeException ex) {
            // 提及是旁路能力，失败不影响发帖/评论
            log.warn("@提及处理失败 channelId={} postId={} message={}", channelId, postId, ex.getMessage());
        }
    }

    /** 供前端联想：频道内可 @ 的成员（含助手）。 */
    public List<MentionCandidateRow> candidates(String channelId, String keyword) {
        List<MentionCandidateRow> rows = channelMemberMapper.selectMentionCandidates(channelId, keyword, 20);
        return rows == null ? List.of() : rows;
    }

    private void notifyMention(MentionCandidateRow candidate, String channelId, String postId, String commentId,
                               String actorId, String snippet, String assistantUserId, Set<String> notified) {
        if (!notified.add(candidate.getId())) {
            return;
        }
        notificationService.notifyUser(candidate.getId(), Notification.Type.MENTION, channelId, postId,
                commentId, actorId, "有人@了你", snippet);
        if (candidate.getId().equals(assistantUserId)) {
            eventPublisher.publishEvent(new AssistantMentionedEvent(channelId, postId, commentId, actorId));
        }
    }

    private Set<String> parseMentionIds(String content) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = MENTION_ID_PATTERN.matcher(content);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private Set<String> parseNames(String content) {
        Set<String> names = new LinkedHashSet<>();
        // 先抹掉 ID 化 token，避免把 @[id:昵称] 里的昵称再当一次手打提及
        String stripped = MENTION_ID_PATTERN.matcher(content).replaceAll(" ");
        Matcher matcher = MENTION_PATTERN.matcher(stripped);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private boolean matches(MentionCandidateRow candidate, String name) {
        String nickname = candidate.getNickname();
        String username = candidate.getUsername();
        return (nickname != null && nickname.equalsIgnoreCase(name))
                || (username != null && username.equalsIgnoreCase(name));
    }

    private String assistantUserId(String channelId) {
        ChannelAiSetting setting = settingMapper.selectOne(Wrappers.<ChannelAiSetting>lambdaQuery()
                .eq(ChannelAiSetting::getChannelId, channelId));
        return setting == null ? null : setting.getAssistantUserId();
    }

    private String snippet(String content) {
        String normalized = MENTION_ID_PATTERN.matcher(content).replaceAll("@$2");
        String plain = normalized.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return plain.length() <= SNIPPET_LENGTH ? plain : plain.substring(0, SNIPPET_LENGTH) + "…";
    }
}
