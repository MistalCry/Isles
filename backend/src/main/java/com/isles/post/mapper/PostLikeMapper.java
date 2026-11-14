package com.isles.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.post.entity.PostLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * 帖子点赞数据访问（唯一约束保证同一用户不重复点赞）。
 */
@Mapper
public interface PostLikeMapper extends BaseMapper<PostLike> {
}
