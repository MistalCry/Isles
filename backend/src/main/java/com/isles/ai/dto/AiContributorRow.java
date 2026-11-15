package com.isles.ai.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 月度贡献榜聚合行（发帖数 + 获赞数）。
 */
@Getter
@Setter
public class AiContributorRow {

    private String userId;
    private String nickname;
    private Long postCount;
    private Long likeSum;
}
