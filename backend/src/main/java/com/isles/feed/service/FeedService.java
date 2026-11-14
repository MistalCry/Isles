package com.isles.feed.service;

import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.pagination.CursorUtils;
import com.isles.common.pagination.PageResult;
import com.isles.post.dto.PostResponse;
import com.isles.post.entity.Post;
import com.isles.post.mapper.PostMapper;
import com.isles.post.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 频道动态（docs/03 FR-FEED-01）：已加入频道的帖子，支持最新/热门与游标分页。
 */
@Service
@RequiredArgsConstructor
public class FeedService {

    private static final String SORT_LATEST = "latest";
    private static final String SORT_HOT = "hot";

    private final PostMapper postMapper;
    private final ChannelMapper channelMapper;
    private final PostService postService;

    public PageResult<PostResponse> feed(String sort, String cursor, Integer limit, String userId) {
        List<String> channelIds = channelMapper.selectJoinedChannels(userId).stream()
                .map(Channel::getId)
                .toList();
        if (channelIds.isEmpty()) {
            return PageResult.empty();
        }
        int pageSize = CursorUtils.normalizeLimit(limit);
        String sortValue = sort == null || sort.isBlank() ? SORT_LATEST : sort;
        List<Post> rows;
        String nextCursor = null;
        if (SORT_LATEST.equals(sortValue)) {
            String cursorId = CursorUtils.validateIdCursor(cursor);
            rows = postMapper.selectFeedLatest(channelIds, cursorId, pageSize + 1);
        } else if (SORT_HOT.equals(sortValue)) {
            CursorUtils.HotCursor hotCursor = CursorUtils.parseHotCursor(cursor);
            rows = postMapper.selectFeedHot(channelIds,
                    hotCursor == null ? null : hotCursor.score(),
                    hotCursor == null ? null : hotCursor.id(),
                    pageSize + 1);
        } else {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "sort 取值必须为 latest 或 hot");
        }
        boolean hasMore = rows.size() > pageSize;
        List<Post> page = hasMore ? rows.subList(0, pageSize) : rows;
        if (hasMore && !page.isEmpty()) {
            Post last = page.get(page.size() - 1);
            nextCursor = SORT_HOT.equals(sortValue)
                    ? CursorUtils.formatHotCursor(hotScore(last), last.getId())
                    : last.getId();
        }
        return new PageResult<>(postService.toResponses(page, userId), nextCursor);
    }

    private long hotScore(Post post) {
        return post.getViewCount() + post.getLikeCount() * 3 + post.getCommentCount() * 5;
    }
}
