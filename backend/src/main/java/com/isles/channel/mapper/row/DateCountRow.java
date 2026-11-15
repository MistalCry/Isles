package com.isles.channel.mapper.row;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 日期聚合行（新增成员趋势等）。
 */
@Getter
@Setter
public class DateCountRow {

    private LocalDate statDate;
    private Long itemCount;
}
