package com.isles.platform.setting;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import com.isles.common.json.JsonSupport;
import com.isles.common.time.ClockTimes;
import com.isles.platform.audit.PlatformAuditActions;
import com.isles.platform.audit.PlatformAuditService;
import com.isles.user.entity.User;
import com.isles.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 平台运行时配置服务（docs/11 §2.2/§6.1）。
 *
 * <p>读取走进程内快照，不查库、不加锁；写入在事务内完成「覆盖值 + 历史」，随后失效快照并写审计。
 * 快照 30 秒兜底过期，用于覆盖「有人直接改库」的场景；多实例下最多 30 秒不一致（单实例部署可接受）。
 */
@Service
@RequiredArgsConstructor
public class PlatformSettingService {

    private static final Logger log = LoggerFactory.getLogger(PlatformSettingService.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final int MAX_VALUE_LENGTH = 2000;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final PlatformSettingMapper settingMapper;
    private final PlatformSettingHistoryMapper historyMapper;
    private final PlatformAuditService auditService;
    private final UserMapper userMapper;
    private final JsonSupport jsonSupport;

    private volatile Snapshot snapshot;

    /**
     * 配置快照：{@code effective} 为「默认值 + 覆盖值」的最终结果，{@code overrides} 仅覆盖值。
     */
    private record Snapshot(Map<String, String> effective, Map<String, String> overrides, long loadedAt) {
    }

    // ------------------------------------------------------------------
    // 读取（消费方入口）
    // ------------------------------------------------------------------

    public String getString(String key) {
        return current().effective().get(SettingDefinitions.require(key).key());
    }

    public int getInt(String key) {
        SettingDefinition definition = SettingDefinitions.require(key);
        String value = current().effective().get(definition.key());
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            // 定义默认值非法或库中被手工改坏：回落到定义默认值，避免业务整体不可用
            log.warn("配置值不是整数，回落默认值 key={} value={}", key, value);
            return Integer.parseInt(definition.defaultValue());
        }
    }

    public boolean getBoolean(String key) {
        SettingDefinition definition = SettingDefinitions.require(key);
        String value = current().effective().get(definition.key());
        return Boolean.parseBoolean(value);
    }

    /**
     * 公开配置：只返回 {@code publicVisible=true} 的项。
     */
    public Map<String, String> publicValues() {
        Snapshot current = current();
        Map<String, String> values = new LinkedHashMap<>();
        for (SettingDefinition definition : SettingDefinitions.all()) {
            if (definition.publicVisible()) {
                values.put(definition.key(), current.effective().get(definition.key()));
            }
        }
        return values;
    }

    // ------------------------------------------------------------------
    // 后台读写
    // ------------------------------------------------------------------

    public PlatformSettingDtos.SettingListView list() {
        Snapshot current = current();
        Map<String, List<PlatformSettingDtos.SettingItem>> grouped = new LinkedHashMap<>();
        for (SettingGroup group : SettingGroup.values()) {
            grouped.put(group.key(), new ArrayList<>());
        }
        for (SettingDefinition definition : SettingDefinitions.all()) {
            String groupKey = SettingGroup.fromKey(definition.group()).key();
            grouped.get(groupKey).add(toItem(definition, current, current.overrides().containsKey(definition.key())));
        }
        List<PlatformSettingDtos.SettingGroupView> groups = new ArrayList<>();
        for (SettingGroup group : SettingGroup.values()) {
            List<PlatformSettingDtos.SettingItem> items = grouped.get(group.key());
            if (!items.isEmpty()) {
                groups.add(new PlatformSettingDtos.SettingGroupView(group.key(), group.label(), List.copyOf(items)));
            }
        }
        return new PlatformSettingDtos.SettingListView(List.copyOf(groups));
    }

    /**
     * 单键更新：校验 → 覆盖值 → 历史 → 失效缓存 → 审计。
     */
    @Transactional
    public PlatformSettingDtos.SettingItem update(String key, String rawValue, String actorId) {
        SettingDefinition definition = SettingDefinitions.require(key);
        String value = validate(definition, rawValue);
        PlatformSetting existing = settingMapper.selectById(key);
        String oldValue = existing == null ? null : existing.getSettingValue();

        writeOverride(key, value, actorId, existing);
        recordHistory(key, oldValue, value, actorId);
        invalidate();
        auditService.record(actorId, actorName(actorId), PlatformAuditActions.CONFIG_UPDATE, "CONFIG", key,
                changeDetail(key, oldValue, value));
        return toItem(definition, value, true);
    }

    /**
     * 批量更新：先整体校验，全部合法才落库，避免部分成功。
     */
    @Transactional
    public List<PlatformSettingDtos.SettingItem> updateBatch(List<PlatformSettingDtos.BatchItem> items,
                                                             String actorId) {
        Map<String, String> validated = new LinkedHashMap<>();
        for (PlatformSettingDtos.BatchItem item : items) {
            SettingDefinition definition = SettingDefinitions.require(item.key());
            validated.put(definition.key(), validate(definition, item.value()));
        }

        List<Map<String, Object>> changes = new ArrayList<>();
        List<PlatformSettingDtos.SettingItem> results = new ArrayList<>();
        for (Map.Entry<String, String> entry : validated.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            PlatformSetting existing = settingMapper.selectById(key);
            String oldValue = existing == null ? null : existing.getSettingValue();
            if (value.equals(oldValue)) {
                // 值未变化：不写历史、不产生审计噪音
                results.add(toItem(SettingDefinitions.require(key), value, existing != null));
                continue;
            }
            writeOverride(key, value, actorId, existing);
            recordHistory(key, oldValue, value, actorId);
            results.add(toItem(SettingDefinitions.require(key), value, true));
            changes.add(changeDetail(key, oldValue, value));
        }
        invalidate();
        if (!changes.isEmpty()) {
            auditService.record(actorId, actorName(actorId), PlatformAuditActions.CONFIG_UPDATE, "CONFIG", null,
                    Map.of("items", changes));
        }
        return results;
    }

    /**
     * 恢复默认：删除覆盖行并留历史（{@code newValue=null} 表示回到默认值）。
     */
    @Transactional
    public PlatformSettingDtos.SettingItem reset(String key, String actorId) {
        SettingDefinition definition = SettingDefinitions.require(key);
        PlatformSetting existing = settingMapper.selectById(key);
        if (existing == null) {
            return toItem(definition, definition.defaultValue(), false);
        }
        settingMapper.deleteByKey(key);
        recordHistory(key, existing.getSettingValue(), null, actorId);
        invalidate();
        auditService.record(actorId, actorName(actorId), PlatformAuditActions.CONFIG_RESET, "CONFIG", key,
                changeDetail(key, existing.getSettingValue(), definition.defaultValue()));
        return toItem(definition, definition.defaultValue(), false);
    }

    public PlatformSettingDtos.HistoryPage history(String key, Integer page, Integer size) {
        String filter = key == null || key.isBlank() ? null : key;
        if (filter != null) {
            SettingDefinitions.require(filter);
        }
        int pageNo = page == null || page <= 0 ? 1 : page;
        int pageSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        long total = historyMapper.countPage(filter);
        List<PlatformSettingHistory> rows = historyMapper.selectPage(filter, pageSize, (pageNo - 1) * pageSize);

        List<String> actorIds = rows.stream()
                .map(PlatformSettingHistory::getActorId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        Map<String, User> actors = actorIds.isEmpty()
                ? Map.of()
                : userMapper.selectBatchIds(actorIds).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));

        List<PlatformSettingDtos.HistoryItem> items = rows.stream()
                .map(row -> new PlatformSettingDtos.HistoryItem(
                        row.getId(),
                        row.getSettingKey(),
                        row.getOldValue(),
                        row.getNewValue(),
                        row.getActorId(),
                        displayName(actors.get(row.getActorId())),
                        row.getIp(),
                        ClockTimes.toInstant(row.getCreatedAt())))
                .toList();
        return new PlatformSettingDtos.HistoryPage(items, total, pageNo, pageSize);
    }

    /**
     * 主动失效快照（供测试或运维接口调用）。
     */
    public void invalidate() {
        snapshot = null;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    private Snapshot current() {
        Snapshot current = snapshot;
        if (current != null && System.currentTimeMillis() - current.loadedAt() < CACHE_TTL.toMillis()) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (current != null && System.currentTimeMillis() - current.loadedAt() < CACHE_TTL.toMillis()) {
                return current;
            }
            return reload();
        }
    }

    private Snapshot reload() {
        List<PlatformSetting> rows = settingMapper.selectList(null);
        Map<String, String> overrides = new HashMap<>();
        for (PlatformSetting row : rows) {
            // 已下线（定义被移除）的键不参与计算，避免残留值影响运行
            if (SettingDefinitions.exists(row.getSettingKey()) && row.getSettingValue() != null) {
                overrides.put(row.getSettingKey(), row.getSettingValue());
            }
        }
        Map<String, String> effective = new LinkedHashMap<>();
        for (SettingDefinition definition : SettingDefinitions.all()) {
            effective.put(definition.key(), overrides.getOrDefault(definition.key(), definition.defaultValue()));
        }
        Snapshot loaded = new Snapshot(Map.copyOf(effective), Map.copyOf(overrides), System.currentTimeMillis());
        snapshot = loaded;
        return loaded;
    }

    private void writeOverride(String key, String value, String actorId, PlatformSetting existing) {
        if (existing == null) {
            PlatformSetting row = new PlatformSetting();
            row.setSettingKey(key);
            row.setSettingValue(value);
            row.setUpdatedBy(actorId);
            row.setUpdatedAt(ClockTimes.nowUtc());
            settingMapper.insert(row);
            return;
        }
        existing.setSettingValue(value);
        existing.setUpdatedBy(actorId);
        existing.setUpdatedAt(ClockTimes.nowUtc());
        settingMapper.updateById(existing);
    }

    private void recordHistory(String key, String oldValue, String newValue, String actorId) {
        PlatformSettingHistory history = new PlatformSettingHistory();
        history.setId(UlidGenerator.generate());
        history.setSettingKey(key);
        history.setOldValue(oldValue);
        history.setNewValue(newValue);
        history.setActorId(actorId);
        history.setIp(currentClientIp());
        history.setCreatedAt(ClockTimes.nowUtc());
        historyMapper.insert(history);
    }

    private String validate(SettingDefinition definition, String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.length() > MAX_VALUE_LENGTH) {
            throw invalid(definition, "配置值最长 " + MAX_VALUE_LENGTH + " 个字符");
        }
        switch (definition.type()) {
            case STRING -> {
                if (definition.minValue() != null && value.length() < definition.minValue()) {
                    throw invalid(definition, "至少 " + definition.minValue() + " 个字符");
                }
                if (definition.maxValue() != null && value.length() > definition.maxValue()) {
                    throw invalid(definition, "最长 " + definition.maxValue() + " 个字符");
                }
            }
            case INT -> {
                int parsed;
                try {
                    parsed = Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                    throw invalid(definition, "必须是整数");
                }
                if (definition.minValue() != null && parsed < definition.minValue()) {
                    throw invalid(definition, "不能小于 " + definition.minValue());
                }
                if (definition.maxValue() != null && parsed > definition.maxValue()) {
                    throw invalid(definition, "不能大于 " + definition.maxValue());
                }
                value = String.valueOf(parsed);
            }
            case BOOL -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw invalid(definition, "只能是 true 或 false");
                }
                value = value.toLowerCase(Locale.ROOT);
            }
            case JSON -> {
                try {
                    jsonSupport.fromJson(value, Object.class);
                } catch (RuntimeException ex) {
                    throw invalid(definition, "必须是合法的 JSON");
                }
            }
        }
        if (!definition.allowedValues().isEmpty() && !definition.allowedValues().contains(value)) {
            throw invalid(definition, "只能是 " + String.join(" / ", definition.allowedValues()));
        }
        return value;
    }

    private AppException invalid(SettingDefinition definition, String reason) {
        return new AppException(ErrorCode.SETTING_VALIDATION_FAILED,
                "配置「" + definition.label() + "」" + reason);
    }

    private PlatformSettingDtos.SettingItem toItem(SettingDefinition definition, Snapshot current, boolean overridden) {
        return toItem(definition, current.effective().get(definition.key()), overridden);
    }

    private PlatformSettingDtos.SettingItem toItem(SettingDefinition definition, String value, boolean overridden) {
        return new PlatformSettingDtos.SettingItem(
                definition.key(),
                SettingGroup.fromKey(definition.group()).key(),
                definition.label(),
                definition.type().name(),
                value,
                definition.defaultValue(),
                overridden,
                definition.publicVisible(),
                definition.description(),
                definition.minValue(),
                definition.maxValue(),
                definition.allowedValues());
    }

    private Map<String, Object> changeDetail(String key, String oldValue, String newValue) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("key", key);
        detail.put("oldValue", oldValue);
        detail.put("newValue", newValue);
        return detail;
    }

    private String actorName(String actorId) {
        if (actorId == null) {
            return null;
        }
        User user = userMapper.selectById(actorId);
        return displayName(user);
    }

    private String displayName(User user) {
        if (user == null) {
            return null;
        }
        return user.getNickname() == null ? user.getUsername() : user.getNickname();
    }

    private String currentClientIp() {
        try {
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
                return servlet.getRequest().getRemoteAddr();
            }
        } catch (RuntimeException ex) {
            // 非 Web 线程
        }
        return null;
    }
}
