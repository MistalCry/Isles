package com.isles.channel.controller;

import com.isles.common.security.CurrentUserProvider;
import com.isles.channel.mapper.row.MentionCandidateRow;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.MentionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @提及候选列表（供编辑器/评论框的 @ 联想使用）。
 *
 * <p>只返回当前频道内的活跃成员（含频道助手虚拟账号）；需要登录且频道对当前用户可见。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MentionController {

    private final MentionService mentionService;
    private final ChannelAccessService accessService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/channels/{channelId}/mention-candidates")
    public List<Candidate> candidates(@PathVariable String channelId,
                                      @RequestParam(required = false) String q) {
        String userId = currentUserProvider.requireUserId();
        accessService.requireVisibleChannel(channelId, userId);
        return mentionService.candidates(channelId, q == null ? null : q.trim()).stream()
                .map(Candidate::of)
                .toList();
    }

    /** 候选成员（字段足够渲染头像 + 昵称）。 */
    public record Candidate(String id, String username, String nickname, String avatarUrl) {
        static Candidate of(MentionCandidateRow row) {
            return new Candidate(row.getId(), row.getUsername(), row.getNickname(), row.getAvatarUrl());
        }
    }
}
