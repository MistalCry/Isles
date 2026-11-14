package com.isles.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.isles.media.entity.MediaFile;
import org.apache.ibatis.annotations.Mapper;

/**
 * 媒体文件数据访问。
 */
@Mapper
public interface MediaFileMapper extends BaseMapper<MediaFile> {
}
