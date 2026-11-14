package com.isles.media.controller;

import com.isles.common.security.CurrentUserProvider;
import com.isles.media.dto.MediaUploadResponse;
import com.isles.media.service.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 媒体上传接口（docs/05 §2.6）：multipart/form-data，字段 file + type=image|video。
 */
@RestController
@RequestMapping("/api/v1/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;
    private final CurrentUserProvider currentUserProvider;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MediaUploadResponse upload(@RequestPart("file") MultipartFile file,
                                      @RequestParam("type") String type) {
        return mediaService.upload(file, type, currentUserProvider.requireUserId());
    }
}
