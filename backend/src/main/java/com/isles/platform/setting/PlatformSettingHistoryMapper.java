package com.isles.platform.setting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 配置变更历史数据访问（只追加）。
 */
@Mapper
public interface PlatformSettingHistoryMapper extends BaseMapper<PlatformSettingHistory> {

    @Select("""
            <script>
            SELECT * FROM platform_setting_histories
            <if test="key != null">WHERE setting_key = #{key}</if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<PlatformSettingHistory> selectPage(@Param("key") String key,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM platform_setting_histories
            <if test="key != null">WHERE setting_key = #{key}</if>
            </script>
            """)
    long countPage(@Param("key") String key);
}
