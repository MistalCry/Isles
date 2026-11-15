package com.isles.manage.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.audit.AuditLogService;
import com.isles.channel.audit.ChannelAuditLog;
import com.isles.channel.dto.BoardResponse;
import com.isles.channel.entity.Board;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.channel.service.ChannelPermissions;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import com.isles.manage.dto.CreateBoardRequest;
import com.isles.manage.dto.ReorderBoardsRequest;
import com.isles.manage.dto.UpdateBoardRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 板块管理（FR-BOARD-02）：创建/重命名/排序/软删除；至少保留一个板块。
 */
@Service
@RequiredArgsConstructor
public class BoardManageService {

    private static final Logger log = LoggerFactory.getLogger(BoardManageService.class);

    private final BoardMapper boardMapper;
    private final ChannelAccessService accessService;
    private final AuditLogService auditLogService;

    /**
     * 板块管理列表（含已删除由前端切换？管理列表默认只返回未删除）。
     */
    public List<BoardResponse> list(String channelId, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.BOARD_MANAGE);
        return listActiveBoards(channelId).stream().map(this::toResponse).toList();
    }

    @Transactional
    public BoardResponse create(String channelId, CreateBoardRequest request, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.BOARD_MANAGE);
        String name = request.name().trim();
        requireUniqueName(channelId, name, null);
        LocalDateTime now = ClockTimes.nowUtc();
        Board board = new Board();
        board.setId(UlidGenerator.generate());
        board.setChannelId(channelId);
        board.setName(name);
        board.setDescription(request.description());
        board.setSort(request.sort() == null ? nextSort(channelId) : request.sort());
        board.setPostCount(0L);
        board.setCreatedAt(now);
        board.setUpdatedAt(now);
        boardMapper.insert(board);
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.BOARD_CREATE, "BOARD", board.getId(),
                Map.of("name", name));
        log.info("创建板块 channelId={} boardId={}", channelId, board.getId());
        return toResponse(board);
    }

    @Transactional
    public BoardResponse update(String channelId, String boardId, UpdateBoardRequest request, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.BOARD_MANAGE);
        Board board = requireBoard(channelId, boardId);
        if (request.name() != null) {
            String name = request.name().trim();
            if (!name.equals(board.getName())) {
                requireUniqueName(channelId, name, boardId);
                board.setName(name);
            }
        }
        if (request.description() != null) {
            board.setDescription(request.description());
        }
        if (request.sort() != null) {
            board.setSort(request.sort());
        }
        board.setUpdatedAt(ClockTimes.nowUtc());
        boardMapper.updateById(board);
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.BOARD_UPDATE, "BOARD", boardId,
                Map.of("name", board.getName(), "sort", board.getSort()));
        return toResponse(board);
    }

    /**
     * 软删除板块：至少保留一个板块；历史帖子仍可通过 board_id 展示板块名。
     */
    @Transactional
    public void delete(String channelId, String boardId, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.BOARD_MANAGE);
        Board board = requireBoard(channelId, boardId);
        long activeCount = listActiveBoards(channelId).size();
        if (activeCount <= 1) {
            throw new AppException(ErrorCode.LAST_BOARD_CANNOT_DELETE);
        }
        board.setDeletedAt(ClockTimes.nowUtc());
        board.setUpdatedAt(ClockTimes.nowUtc());
        boardMapper.updateById(board);
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.BOARD_DELETE, "BOARD", boardId,
                Map.of("name", board.getName()));
        log.info("删除板块 channelId={} boardId={}", channelId, boardId);
    }

    /**
     * 板块排序：要求传入的板块集合与当前未删除板块完全一致。
     */
    @Transactional
    public List<BoardResponse> reorder(String channelId, ReorderBoardsRequest request, String actorId) {
        accessService.requirePermission(channelId, actorId, ChannelPermissions.BOARD_MANAGE);
        List<Board> activeBoards = listActiveBoards(channelId);
        Set<String> activeIds = activeBoards.stream().map(Board::getId).collect(java.util.stream.Collectors.toSet());
        Set<String> requestedIds = new HashSet<>(request.boardIds());
        if (requestedIds.size() != request.boardIds().size() || !activeIds.equals(requestedIds)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, "板块顺序与当前板块不一致");
        }
        LocalDateTime now = ClockTimes.nowUtc();
        for (int index = 0; index < request.boardIds().size(); index++) {
            Board board = new Board();
            board.setId(request.boardIds().get(index));
            board.setSort(index);
            board.setUpdatedAt(now);
            boardMapper.updateById(board);
        }
        auditLogService.record(channelId, actorId, ChannelAuditLog.Action.BOARD_REORDER, "BOARD", null,
                Map.of("boardIds", request.boardIds()));
        return listActiveBoards(channelId).stream().map(this::toResponse).toList();
    }

    private List<Board> listActiveBoards(String channelId) {
        return boardMapper.selectList(Wrappers.<Board>lambdaQuery()
                .eq(Board::getChannelId, channelId)
                .isNull(Board::getDeletedAt)
                .orderByAsc(Board::getSort)
                .orderByAsc(Board::getCreatedAt));
    }

    private Board requireBoard(String channelId, String boardId) {
        Board board = boardMapper.selectById(boardId);
        if (board == null || board.getDeletedAt() != null || !channelId.equals(board.getChannelId())) {
            throw new AppException(ErrorCode.BOARD_NOT_FOUND);
        }
        return board;
    }

    private void requireUniqueName(String channelId, String name, String excludeBoardId) {
        Long count = boardMapper.selectCount(Wrappers.<Board>lambdaQuery()
                .eq(Board::getChannelId, channelId)
                .eq(Board::getName, name)
                .isNull(Board::getDeletedAt)
                .ne(excludeBoardId != null, Board::getId, excludeBoardId));
        if (count != null && count > 0) {
            throw new AppException(ErrorCode.CONFLICT, "板块名称已存在");
        }
    }

    private int nextSort(String channelId) {
        return listActiveBoards(channelId).size();
    }

    private BoardResponse toResponse(Board board) {
        return new BoardResponse(board.getId(), board.getChannelId(), board.getName(), board.getDescription(),
                board.getSort(), board.getPostCount(), ClockTimes.toInstant(board.getCreatedAt()));
    }
}
