package com.isles.manage.controller;

import com.isles.common.pagination.OffsetPage;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.manage.dto.HandleReportRequest;
import com.isles.manage.dto.ReportCreateRequest;
import com.isles.manage.dto.ReportResponse;
import com.isles.manage.service.ReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 举报接口（FR-ITR-03）：用户创建/我的举报、频道管理员列表与处置。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse createReport(@Valid @RequestBody ReportCreateRequest request) {
        return reportService.create(request, currentUserProvider.requireUserId());
    }

    @GetMapping("/reports/mine")
    public PageResult<ReportResponse> myReports(@RequestParam(required = false) String cursor,
                                                @RequestParam(required = false) Integer limit) {
        return reportService.listMine(currentUserProvider.requireUserId(), cursor, limit);
    }

    @GetMapping("/channels/{channelId}/reports")
    public OffsetPage<ReportResponse> listChannelReports(@PathVariable String channelId,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String targetType,
                                                         @RequestParam(required = false, defaultValue = "1") Integer page,
                                                         @RequestParam(required = false, defaultValue = "20") Integer size) {
        return reportService.listChannelOffset(channelId, status, targetType, page, size,
                currentUserProvider.requireUserId());
    }

    @PostMapping("/channels/{channelId}/reports/{reportId}/handle")
    public ReportResponse handleReport(@PathVariable String channelId, @PathVariable String reportId,
                                       @Valid @RequestBody HandleReportRequest request) {
        return reportService.handle(channelId, reportId, request, currentUserProvider.requireUserId());
    }
}
