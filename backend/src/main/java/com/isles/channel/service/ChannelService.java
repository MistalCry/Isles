package com.isles.channel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.dto.BoardResponse;
import com.isles.channel.dto.ChannelResponse;
import com.isles.channel.dto.CreateChannelRequest;
import com.isles.channel.dto.InviteAcceptResponse;
import com.isles.channel.dto.InviteCreateRequest;
import com.isles.channel.dto.InviteCreateResponse;
import com.isles.channel.dto.MemberCardInfo;
import com.isles.channel.entity.Board;
import com.isles.channel.entity.Channel;
import com.isles.channel.entity.ChannelInvite;
import com.isles.channel.entity.ChannelMember;
import com.isles.channel.entity.JoinRequest;
import com.isles.channel.entity.Role;
import com.isles.channel.mapper.BoardMapper;
import com.isles.channel.mapper.ChannelInviteMapper;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.mapper.ChannelMemberMapper;
import com.isles.channel.mapper.JoinRequestMapper;
import com.isles.channel.mapper.RoleMapper;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.json.JsonSupport;
import com.isles.common.security.TokenHasher;
import com.isles.common.time.ClockTimes;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 频道应用服务：创建频道（事务内生成默认板块 + 四个系统身份组 + 频道主成员）、详情、我的频道、加入、
 * 板块列表、邀请链接创建与使用。
 *
 * <p>所有可见性/权限校验委托 {@link ChannelAccessService}（docs/03 §5.2）。
 */
@Service
@RequiredArgsConstructor
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);

    private static final String DEFAULT_BOARD_NAME = "综合讨论";
    private static final long MIN_EXPIRES_SECONDS = 60L;
    private static final long MAX_EXPIRES_SECONDS = 30L * 24 * 3600;
    private static final long DEFAULT_EXPIRES_SECONDS = 7L * 24 * 3600;
    private static final long CHANNEL_NO_MIN = 10_000_000L;
    private static final long CHANNEL_NO_RANGE = 90_000_000L;

    private final ChannelMapper channelMapper;
    private final BoardMapper boardMapper;
    private final RoleMapper roleMapper;
    private final ChannelMemberMapper channelMemberMapper;
    private final ChannelInviteMapper channelInviteMapper;
    private final JoinRequestMapper joinRequestMapper;
    private final ChannelAccessService accessService;
    private final JsonSupport jsonSupport;

    /**
     * 创建频道：单事务内完成频道、默认板块、四个系统身份组、频道主成员关系的创建，失败整体回滚。
     */
    @Transactional
    public ChannelResponse createChannel(CreateChannelRequest request, String ownerId) {
        LocalDateTime now = ClockTimes.nowUtc();
        Channel channel = new Channel();
        channel.setId(UlidGenerator.generate());
        channel.setChannelNo(generateUniqueChannelNo());
        channel.setName(request.name().trim());
        channel.setAvatarUrl(request.avatarUrl());
        channel.setCoverUrl(request.coverUrl());
        channel.setDescription(request.description());
        String type = request.type() == null ? Channel.Type.PUBLIC.name() : request.type();
        channel.setType(type);
        channel.setVisibility(resolveVisibility(type, request.visibility()));
        channel.setOwnerId(ownerId);
        channel.setMemberCount(1L);
        channel.setPostCount(0L);
        channel.setStatus(Channel.Status.ACTIVE.name());
        channel.setCreatedAt(now);
        channel.setUpdatedAt(now);
        channelMapper.insert(channel);

        Board defaultBoard = new Board();
        defaultBoard.setId(UlidGenerator.generate());
        defaultBoard.setChannelId(channel.getId());
        defaultBoard.setName(DEFAULT_BOARD_NAME);
        defaultBoard.setDescription("频道默认板块");
        defaultBoard.setSort(0);
        defaultBoard.setPostCount(0L);
        defaultBoard.setCreatedAt(now);
        defaultBoard.setUpdatedAt(now);
        boardMapper.insert(defaultBoard);

        Map<String, String> roleIds = new HashMap<>();
        for (SystemRoles.Definition definition : SystemRoles.definitions()) {
            Role role = new Role();
            role.setId(UlidGenerator.generate());
            role.setChannelId(channel.getId());
            role.setName(definition.name());
            role.setDescription(definition.description());
            role.setPermissions(jsonSupport.toJson(definition.permissions()));
            role.setIsSystem(true);
            role.setSort(definition.sort());
            role.setCreatedAt(now);
            role.setUpdatedAt(now);
            roleMapper.insert(role);
            roleIds.put(definition.name(), role.getId());
        }

        ChannelMember ownerMember = new ChannelMember();
        ownerMember.setId(UlidGenerator.generate());
        ownerMember.setChannelId(channel.getId());
        ownerMember.setUserId(ownerId);
        ownerMember.setRoleId(roleIds.get(SystemRoles.OWNER));
        ownerMember.setStatus(ChannelMember.Status.ACTIVE.name());
        ownerMember.setJoinedAt(now);
        ownerMember.setCreatedAt(now);
        ownerMember.setUpdatedAt(now);
        channelMemberMapper.insert(ownerMember);

        log.info("频道创建成功 channelId={} channelNo={}", channel.getId(), channel.getChannelNo());
        return toResponse(channel, true, SystemRoles.OWNER);
    }

    /**
     * 频道详情：按可见性校验（游客可看公开频道）。
     */
    public ChannelResponse getChannel(String channelId, String viewerId) {
        Channel channel = accessService.requireVisibleChannel(channelId, viewerId);
        ChannelMember member = accessService.findMember(channelId, viewerId);
        boolean joined = ChannelAccessService.isActiveMember(member);
        String roleName = null;
        if (joined) {
            Role role = accessService.findRole(channelId, member.getRoleId());
            roleName = role == null ? null : role.getName();
        }
        return toResponse(channel, joined, roleName);
    }

    /**
     * 我加入的频道（侧边栏数据源）。
     */
    public List<ChannelResponse> getMyChannels(String userId) {
        return channelMapper.selectJoinedChannels(userId).stream()
                .map(channel -> toResponse(channel, true, channel.getRoleName()))
                .toList();
    }

    /**
     * 加入公开频道（私密频道必须走邀请链接）。
     */
    @Transactional
    public ChannelResponse joinChannel(String channelId, String userId) {
        Channel channel = accessService.requireVisibleChannel(channelId, userId);
        if (!Channel.Type.PUBLIC.name().equals(channel.getType())) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "私密频道仅可通过邀请链接加入");
        }
        ChannelMember member = accessService.findMember(channelId, userId);
        if (ChannelAccessService.isActiveMember(member)) {
            throw new AppException(ErrorCode.ALREADY_JOINED);
        }
        String memberRoleId = requireMemberRoleId(channelId);
        LocalDateTime now = ClockTimes.nowUtc();
        if (member == null) {
            ChannelMember newMember = new ChannelMember();
            newMember.setId(UlidGenerator.generate());
            newMember.setChannelId(channelId);
            newMember.setUserId(userId);
            newMember.setRoleId(memberRoleId);
            newMember.setStatus(ChannelMember.Status.ACTIVE.name());
            newMember.setJoinedAt(now);
            newMember.setCreatedAt(now);
            newMember.setUpdatedAt(now);
            channelMemberMapper.insert(newMember);
        } else {
            // 曾退出/被移除后重新加入：复用原记录（唯一约束 channel_id + user_id）
            member.setRoleId(memberRoleId);
            member.setStatus(ChannelMember.Status.ACTIVE.name());
            member.setMutedUntil(null);
            member.setJoinedAt(now);
            member.setUpdatedAt(now);
            channelMemberMapper.updateById(member);
        }
        channelMapper.addMemberCount(channelId, 1);
        channel.setMemberCount(channel.getMemberCount() + 1);
        log.info("加入频道成功 channelId={} userId={}", channelId, userId);
        return toResponse(channel, true, SystemRoles.MEMBER);
    }

    /**
     * 板块列表（频道可见即可读）。
     */
    public List<BoardResponse> listBoards(String channelId, String viewerId) {
        accessService.requireVisibleChannel(channelId, viewerId);
        return boardMapper.selectList(Wrappers.<Board>lambdaQuery()
                        .eq(Board::getChannelId, channelId)
                        .orderByAsc(Board::getSort)
                        .orderByAsc(Board::getCreatedAt))
                .stream()
                .map(this::toBoardResponse)
                .toList();
    }

    /**
     * 创建邀请链接：需要 {@code channel.invite.create} 权限（频道主/超级管理员）。
     */
    @Transactional
    public InviteCreateResponse createInvite(String channelId, InviteCreateRequest request, String userId) {
        accessService.requirePermission(channelId, userId, ChannelPermissions.INVITE_CREATE);
        long expiresIn = request.expiresInSeconds() == null
                ? DEFAULT_EXPIRES_SECONDS
                : Math.min(Math.max(request.expiresInSeconds(), MIN_EXPIRES_SECONDS), MAX_EXPIRES_SECONDS);
        int maxUses = request.maxUses() == null ? 0 : request.maxUses();
        String rawToken = TokenHasher.randomToken();
        LocalDateTime now = ClockTimes.nowUtc();
        ChannelInvite invite = new ChannelInvite();
        invite.setId(UlidGenerator.generate());
        invite.setChannelId(channelId);
        invite.setTokenHash(TokenHasher.sha256Hex(rawToken));
        invite.setRequiresApproval(Boolean.TRUE.equals(request.requiresApproval()));
        invite.setExpiresAt(now.plusSeconds(expiresIn));
        invite.setMaxUses(maxUses == 0 ? null : maxUses);
        invite.setUsedCount(0);
        invite.setCreatedBy(userId);
        invite.setCreatedAt(now);
        channelInviteMapper.insert(invite);
        log.info("创建邀请链接 channelId={} inviteId={} requiresApproval={}", channelId, invite.getId(),
                invite.getRequiresApproval());
        return new InviteCreateResponse(invite.getId(), rawToken, "/invites/" + rawToken,
                invite.getRequiresApproval(), ClockTimes.toInstant(invite.getExpiresAt()), invite.getMaxUses());
    }

    /**
     * 使用邀请链接：立即加入，或（需审批时）创建待审批申请。
     */
    @Transactional
    public InviteAcceptResponse acceptInvite(String rawToken, String userId) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AppException(ErrorCode.INVITE_INVALID);
        }
        ChannelInvite invite = channelInviteMapper.selectOne(Wrappers.<ChannelInvite>lambdaQuery()
                .eq(ChannelInvite::getTokenHash, TokenHasher.sha256Hex(rawToken)));
        if (invite == null || invite.getRevokedAt() != null) {
            throw new AppException(ErrorCode.INVITE_INVALID);
        }
        LocalDateTime now = ClockTimes.nowUtc();
        if (invite.getExpiresAt() != null && !invite.getExpiresAt().isAfter(now)) {
            throw new AppException(ErrorCode.INVITE_EXPIRED);
        }
        if (invite.getMaxUses() != null && invite.getUsedCount() >= invite.getMaxUses()) {
            throw new AppException(ErrorCode.INVITE_EXHAUSTED);
        }
        Channel channel = accessService.requireActiveChannel(invite.getChannelId());
        ChannelMember member = accessService.findMember(channel.getId(), userId);
        if (ChannelAccessService.isActiveMember(member)) {
            // 幂等：已是成员直接返回成功，不重复计数
            return new InviteAcceptResponse("JOINED", channel.getId(), null);
        }
        if (Boolean.TRUE.equals(invite.getRequiresApproval())) {
            Long pending = joinRequestMapper.selectCount(Wrappers.<JoinRequest>lambdaQuery()
                    .eq(JoinRequest::getChannelId, channel.getId())
                    .eq(JoinRequest::getUserId, userId)
                    .eq(JoinRequest::getStatus, JoinRequest.Status.PENDING.name()));
            if (pending != null && pending > 0) {
                throw new AppException(ErrorCode.ALREADY_APPLIED);
            }
            JoinRequest joinRequest = new JoinRequest();
            joinRequest.setId(UlidGenerator.generate());
            joinRequest.setChannelId(channel.getId());
            joinRequest.setUserId(userId);
            joinRequest.setInviteId(invite.getId());
            joinRequest.setStatus(JoinRequest.Status.PENDING.name());
            joinRequest.setCreatedAt(now);
            joinRequestMapper.insert(joinRequest);
            return new InviteAcceptResponse("PENDING", channel.getId(), joinRequest.getId());
        }
        addMember(channel.getId(), member, userId, now);
        // 使用次数以条件更新占用名额，避免并发下超过 max_uses
        if (channelInviteMapper.incrementUsedCount(invite.getId()) == 0) {
            throw new AppException(ErrorCode.INVITE_EXHAUSTED);
        }
        log.info("通过邀请加入频道 channelId={} userId={}", channel.getId(), userId);
        return new InviteAcceptResponse("JOINED", channel.getId(), null);
    }

    /**
     * 用户卡片在指定频道内的加入时间/身份组。
     */
    public MemberCardInfo getMemberCardInfo(String channelId, String targetUserId, String viewerId) {
        Channel channel = accessService.requireVisibleChannel(channelId, viewerId);
        ChannelMember member = accessService.findMember(channelId, targetUserId);
        if (member == null) {
            return new MemberCardInfo(channel.getId(), null, null, null);
        }
        Role role = accessService.findRole(channelId, member.getRoleId());
        return new MemberCardInfo(channel.getId(), ClockTimes.toInstant(member.getJoinedAt()),
                role == null ? null : role.getName(), member.getStatus());
    }

    private void addMember(String channelId, ChannelMember existingMember, String userId, LocalDateTime now) {
        String memberRoleId = requireMemberRoleId(channelId);
        if (existingMember == null) {
            ChannelMember newMember = new ChannelMember();
            newMember.setId(UlidGenerator.generate());
            newMember.setChannelId(channelId);
            newMember.setUserId(userId);
            newMember.setRoleId(memberRoleId);
            newMember.setStatus(ChannelMember.Status.ACTIVE.name());
            newMember.setJoinedAt(now);
            newMember.setCreatedAt(now);
            newMember.setUpdatedAt(now);
            channelMemberMapper.insert(newMember);
        } else {
            existingMember.setRoleId(memberRoleId);
            existingMember.setStatus(ChannelMember.Status.ACTIVE.name());
            existingMember.setMutedUntil(null);
            existingMember.setJoinedAt(now);
            existingMember.setUpdatedAt(now);
            channelMemberMapper.updateById(existingMember);
        }
        channelMapper.addMemberCount(channelId, 1);
    }

    private String requireMemberRoleId(String channelId) {
        Role memberRole = roleMapper.selectOne(Wrappers.<Role>lambdaQuery()
                .eq(Role::getChannelId, channelId)
                .eq(Role::getName, SystemRoles.MEMBER));
        if (memberRole == null) {
            // 数据一致性错误：创建频道时必须生成普通成员身份组
            throw new AppException(ErrorCode.INTERNAL_ERROR, "频道系统身份组缺失");
        }
        return memberRole.getId();
    }

    private String resolveVisibility(String type, String requestedVisibility) {
        if (requestedVisibility != null) {
            return requestedVisibility;
        }
        return Channel.Type.PRIVATE.name().equals(type)
                ? Channel.Visibility.MEMBERS_ONLY.name()
                : Channel.Visibility.PUBLIC_VISIBLE.name();
    }

    private long generateUniqueChannelNo() {
        for (int attempt = 0; attempt < 10; attempt++) {
            long candidate = CHANNEL_NO_MIN + ThreadLocalRandom.current().nextLong(CHANNEL_NO_RANGE);
            Long count = channelMapper.selectCount(Wrappers.<Channel>lambdaQuery().eq(Channel::getChannelNo, candidate));
            if (count == null || count == 0) {
                return candidate;
            }
        }
        throw new AppException(ErrorCode.INTERNAL_ERROR, "频道号生成失败，请重试");
    }

    private ChannelResponse toResponse(Channel channel, boolean joined, String roleName) {
        return new ChannelResponse(
                channel.getId(),
                channel.getChannelNo(),
                channel.getName(),
                channel.getAvatarUrl(),
                channel.getCoverUrl(),
                channel.getDescription(),
                channel.getType(),
                channel.getVisibility(),
                channel.getOwnerId(),
                channel.getMemberCount(),
                channel.getPostCount(),
                joined,
                joined ? roleName : null,
                ClockTimes.toInstant(channel.getCreatedAt()));
    }

    private BoardResponse toBoardResponse(Board board) {
        return new BoardResponse(board.getId(), board.getChannelId(), board.getName(), board.getDescription(),
                board.getSort(), board.getPostCount(), ClockTimes.toInstant(board.getCreatedAt()));
    }
}
