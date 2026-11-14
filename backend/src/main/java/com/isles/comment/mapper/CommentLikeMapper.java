package com.isles.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.comment.entity.CommentLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评论点赞数据访问（唯一约束保证同一用户不重复点赞）。
 */
@Mapper
public interface CommentLikeMapper extends BaseMapper<CommentLike> {
}
