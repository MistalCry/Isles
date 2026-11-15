package com.isles.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.social.entity.FriendRelation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 好友关系数据访问（V4 {@code friend_relations}）。
 *
 * <p>查询均带 {@code status}，索引见迁移脚本：好友列表走 idx_friend_relations_user_status，
 * 待处理申请走 idx_friend_relations_target_status。
 */
@Mapper
public interface FriendRelationMapper extends BaseMapper<FriendRelation> {

    /**
     * 查询两个用户之间的双向关系行（最多两条：A→B、B→A）。
     */
    @Select("""
            SELECT * FROM friend_relations
            WHERE (user_id = #{userId} AND target_user_id = #{targetUserId})
               OR (user_id = #{targetUserId} AND target_user_id = #{userId})
            """)
    List<FriendRelation> selectPair(@Param("userId") String userId, @Param("targetUserId") String targetUserId);

    /**
     * 我发起的指定状态关系（好友列表 / 我拉黑的人 / 我发出的申请），ULID 倒序游标分页。
     */
    @Select("""
            <script>
            SELECT * FROM friend_relations
            WHERE user_id = #{userId} AND status = #{status}
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<FriendRelation> selectByUserAndStatus(@Param("userId") String userId,
                                               @Param("status") String status,
                                               @Param("cursorId") String cursorId,
                                               @Param("limit") int limit);

    /**
     * 别人发给我的待处理申请（target_user_id = 我），ULID 倒序游标分页。
     */
    @Select("""
            <script>
            SELECT * FROM friend_relations
            WHERE target_user_id = #{userId} AND status = 'PENDING'
            <if test="cursorId != null">AND id &lt; #{cursorId}</if>
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<FriendRelation> selectIncomingPending(@Param("userId") String userId,
                                               @Param("cursorId") String cursorId,
                                               @Param("limit") int limit);

    /**
     * 清理两人之间的非拉黑关系（删除好友 / 拉黑前清理申请与好友行）。
     * 拉黑行只能由拉黑方本人通过“取消拉黑”删除，因此这里显式排除。
     */
    @Delete("""
            DELETE FROM friend_relations
            WHERE ((user_id = #{userId} AND target_user_id = #{targetUserId})
                OR (user_id = #{targetUserId} AND target_user_id = #{userId}))
              AND status != 'BLOCKED'
            """)
    int deleteNonBlockedBetween(@Param("userId") String userId, @Param("targetUserId") String targetUserId);

    /**
     * 取消拉黑：只允许主体本人删除自己的拉黑行，幂等。
     */
    @Delete("""
            DELETE FROM friend_relations
            WHERE user_id = #{userId} AND target_user_id = #{targetUserId} AND status = 'BLOCKED'
            """)
    int deleteBlocked(@Param("userId") String userId, @Param("targetUserId") String targetUserId);
}
