package com.isles.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.post.entity.PostMedia;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 帖子媒体数据访问。
 */
@Mapper
public interface PostMediaMapper extends BaseMapper<PostMedia> {

    /**
     * 批量取多个帖子的媒体，按「帖子 → sort → id」升序，调用方取每个帖子的第一条即首图。
     *
     * <p>搜索列表用：一次查询替代逐帖查询，避免 N+1。
     */
    @Select("""
            <script>
            SELECT * FROM post_media
            WHERE post_id IN
            <foreach item="postId" collection="postIds" open="(" separator="," close=")">
              #{postId}
            </foreach>
            ORDER BY post_id ASC, sort ASC, id ASC
            </script>
            """)
    List<PostMedia> selectByPostIds(@Param("postIds") Collection<String> postIds);
}
