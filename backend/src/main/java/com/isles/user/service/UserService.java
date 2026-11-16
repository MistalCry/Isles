package com.isles.user.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.isles.channel.dto.MemberCardInfo;
import com.isles.channel.service.ChannelService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.time.ClockTimes;
import com.isles.user.dto.UpdateProfileRequest;
import com.isles.user.dto.UserCardResponse;
import com.isles.user.dto.UserResponse;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 用户资料服务：注册时的账号创建/查询、公开资料、用户卡片、资料修改。
 *
 * <p>密码哈希由 auth 模块生成后传入，本服务不接触明文密码。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserMapper userMapper;
    private final ChannelService channelService;

    public boolean existsByUsername(String username) {
        Long count = userMapper.selectCount(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)
                .isNull(User::getDeletedAt));
        return count != null && count > 0;
    }

    public Optional<User> findByUsername(String username) {
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getUsername, username)
                .isNull(User::getDeletedAt));
        return Optional.ofNullable(user);
    }

    /**
     * 创建账号：调用方（AuthService）已完成用户名唯一性与密码强度校验。
     */
    @Transactional
    public User createUser(String username, String passwordHash, String nickname) {
        LocalDateTime now = ClockTimes.nowUtc();
        User user = new User();
        user.setId(UlidGenerator.generate());
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setNickname(nickname == null || nickname.isBlank() ? username : nickname.trim());
        user.setGender(User.Gender.UNKNOWN.name());
        user.setStatus(User.Status.ACTIVE.name());
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        log.info("用户注册成功 userId={}", user.getId());
        return user;
    }

    /**
     * 公开资料；用户不存在、已删除或被封禁一律 404（不泄露账号状态）。
     */
    public User requireActiveById(String userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getDeletedAt() != null || !User.Status.ACTIVE.name().equals(user.getStatus())) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    public UserResponse getPublicUser(String userId) {
        return toResponse(requireActiveById(userId));
    }

    /**
     * 用户卡片：可选返回在指定频道内的加入时间/身份组（FR-USER-02）。
     */
    public UserCardResponse getUserCard(String userId, String channelId, String viewerId) {
        User user = requireActiveById(userId);
        MemberCardInfo memberInfo = null;
        if (channelId != null && !channelId.isBlank()) {
            memberInfo = channelService.getMemberCardInfo(channelId, userId, viewerId);
        }
        return new UserCardResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getSignature(),
                user.getGender(),
                user.getRegion(),
                memberInfo == null ? null : memberInfo.channelId(),
                memberInfo == null ? null : memberInfo.joinedAt(),
                memberInfo == null ? null : memberInfo.roleName(),
                memberInfo == null ? null : memberInfo.memberStatus(),
                Boolean.TRUE.equals(user.getIsAgent()));
    }

    /**
     * 修改头像/昵称/签名/性别/地区；未传字段保持不变。
     */
    @Transactional
    public UserResponse updateProfile(String userId, UpdateProfileRequest request) {
        User user = requireActiveById(userId);
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl());
        }
        if (request.nickname() != null) {
            String nickname = request.nickname().trim();
            if (nickname.isEmpty()) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, "昵称不能为空白字符");
            }
            user.setNickname(nickname);
        }
        if (request.signature() != null) {
            user.setSignature(request.signature());
        }
        if (request.gender() != null) {
            user.setGender(request.gender());
        }
        if (request.region() != null) {
            user.setRegion(request.region());
        }
        user.setUpdatedAt(ClockTimes.nowUtc());
        userMapper.updateById(user);
        return toResponse(user);
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getAvatarUrl(),
                user.getSignature(),
                user.getGender(),
                user.getRegion(),
                ClockTimes.toInstant(user.getCreatedAt()),
                Boolean.TRUE.equals(user.getIsAgent()));
    }
}
