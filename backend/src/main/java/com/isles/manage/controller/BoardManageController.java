package com.isles.manage.controller;

import com.isles.channel.dto.BoardResponse;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.manage.dto.CreateBoardRequest;
import com.isles.manage.dto.ReorderBoardsRequest;
import com.isles.manage.dto.UpdateBoardRequest;
import com.isles.manage.service.BoardManageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 板块管理（FR-BOARD-02）。
 */
@RestController
@RequestMapping("/api/v1/channels")
@RequiredArgsConstructor
public class BoardManageController {

    private final BoardManageService boardManageService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/{id}/boards/manage")
    public PageResult<BoardResponse> listBoards(@PathVariable String id) {
        return new PageResult<>(boardManageService.list(id, currentUserProvider.requireUserId()), null);
    }

    @PostMapping("/{id}/boards")
    @ResponseStatus(HttpStatus.CREATED)
    public BoardResponse createBoard(@PathVariable String id, @Valid @RequestBody CreateBoardRequest request) {
        return boardManageService.create(id, request, currentUserProvider.requireUserId());
    }

    @PatchMapping("/{id}/boards/reorder")
    public PageResult<BoardResponse> reorderBoards(@PathVariable String id,
                                                   @Valid @RequestBody ReorderBoardsRequest request) {
        return new PageResult<>(boardManageService.reorder(id, request, currentUserProvider.requireUserId()), null);
    }

    @PatchMapping("/{id}/boards/{boardId}")
    public BoardResponse updateBoard(@PathVariable String id, @PathVariable String boardId,
                                     @Valid @RequestBody UpdateBoardRequest request) {
        return boardManageService.update(id, boardId, request, currentUserProvider.requireUserId());
    }

    @DeleteMapping("/{id}/boards/{boardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBoard(@PathVariable String id, @PathVariable String boardId) {
        boardManageService.delete(id, boardId, currentUserProvider.requireUserId());
    }
}
