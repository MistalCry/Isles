package com.isles.admin.controller;

import com.isles.common.security.CurrentUserProvider;
import com.isles.platform.permission.PlatformPermission;
import com.isles.platform.permission.RequirePlatformPermission;
import com.isles.platform.wordfilter.SensitiveWordDtos;
import com.isles.platform.wordfilter.SensitiveWordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 敏感词管理接口（docs/11 §5.5）：词库 CRUD、批量导入、试算、命中记录查询。
 *
 * <p>当前版本只记录命中（REVIEW），不阻断内容发布。
 */
@RestController
@RequestMapping("/api/v1/admin/sensitive-words")
@RequiredArgsConstructor
public class AdminSensitiveWordController {

    private final SensitiveWordService wordService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    @RequirePlatformPermission(PlatformPermission.CONFIG_READ)
    public SensitiveWordDtos.WordPage list(@RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return wordService.list(keyword, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePlatformPermission(PlatformPermission.WORD_MANAGE)
    public SensitiveWordDtos.WordView create(@Valid @RequestBody SensitiveWordDtos.SaveWordRequest request) {
        return wordService.create(request, currentUserProvider.requireUserId());
    }

    @PutMapping("/{id}")
    @RequirePlatformPermission(PlatformPermission.WORD_MANAGE)
    public SensitiveWordDtos.WordView update(@PathVariable String id,
                                             @Valid @RequestBody SensitiveWordDtos.SaveWordRequest request) {
        return wordService.update(id, request, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePlatformPermission(PlatformPermission.WORD_MANAGE)
    public void delete(@PathVariable String id) {
        wordService.delete(id, currentUserProvider.requireUserId());
    }

    @PostMapping("/import")
    @RequirePlatformPermission(PlatformPermission.WORD_MANAGE)
    public SensitiveWordDtos.ImportResult importWords(
            @Valid @RequestBody SensitiveWordDtos.ImportRequest request) {
        return wordService.importWords(request, currentUserProvider.requireUserId());
    }

    /**
     * 试算：校验一段文本会命中哪些词，不写库。
     */
    @PostMapping("/scan")
    @RequirePlatformPermission(PlatformPermission.WORD_MANAGE)
    public SensitiveWordDtos.ScanResult scan(@Valid @RequestBody SensitiveWordDtos.ScanRequest request) {
        return wordService.preview(request.text());
    }

    @GetMapping("/hits")
    @RequirePlatformPermission(PlatformPermission.CONFIG_READ)
    public SensitiveWordDtos.HitPage hits(@RequestParam(required = false) String scene,
                                          @RequestParam(required = false) String word,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return wordService.hits(scene, word, page, size);
    }
}
