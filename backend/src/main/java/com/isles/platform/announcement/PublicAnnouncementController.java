package com.isles.platform.announcement;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 公开公告接口（docs/11 §5.5）：前台公告条读取，只返回生效中的公告。
 *
 * <p>允许客户端缓存 60 秒；发布/归档后最迟 1 分钟在前台生效。
 */
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
public class PublicAnnouncementController {

    private static final CacheControl PUBLIC_CACHE = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final AnnouncementService announcementService;

    @GetMapping("/announcements")
    public ResponseEntity<AnnouncementDtos.PublicAnnouncementResponse> active() {
        return ResponseEntity.ok()
                .cacheControl(PUBLIC_CACHE)
                .body(new AnnouncementDtos.PublicAnnouncementResponse(announcementService.active()));
    }
}
