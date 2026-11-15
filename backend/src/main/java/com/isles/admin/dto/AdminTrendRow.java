package com.isles.admin.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 趋势聚合行：{@code day} 为 UTC 自然日，{@code total} 为该日计数。
 */
@Getter
@Setter
public class AdminTrendRow {

    private LocalDate day;
    private Long total;
}
