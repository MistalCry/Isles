package com.isles.platform.setting;

import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台配置注册表（docs/11 §2.2）：所有可配置项在此声明，是默认值与校验规则的唯一事实来源。
 *
 * <p>约定：
 * <ul>
 *   <li>键名 {@code 分组.语义}，只增不改（键是后台与审计的稳定标识）；</li>
 *   <li>{@code publicVisible=true} 的项会出现在公开接口，禁止放置密钥、内部地址等敏感值；</li>
 *   <li>默认值必须能被对应 {@link SettingType} 解析，{@link #validateAll()} 在单测中守护。</li>
 * </ul>
 */
public final class SettingDefinitions {

    private static final int MAX_TEXT_LENGTH = 2000;

    private static final List<SettingDefinition> ALL = List.of(
            // ---- 站点信息 ----
            text("site.name", SettingGroup.SITE, "站点名称", "群岛", true,
                    "显示在浏览器标题、侧边栏与分享卡片上", 1, 40),
            text("site.logoUrl", SettingGroup.SITE, "站点 Logo", "", true,
                    "图片地址，留空使用默认文字标识", 0, 500),
            text("site.description", SettingGroup.SITE, "站点简介", "每个频道是一座岛，在群岛之间找到同频的人。", true,
                    "用于登录页与分享描述", 0, 200),
            text("site.icp", SettingGroup.SITE, "备案号", "", true,
                    "展示在页脚，留空则不显示", 0, 60),
            text("site.contactEmail", SettingGroup.SITE, "联系邮箱", "", true,
                    "用户申诉与问题反馈入口，留空则不显示", 0, 120),

            // ---- 注册与准入 ----
            bool("auth.register.enabled", SettingGroup.AUTH, "开放注册", "true", true,
                    "关闭后新用户无法自助注册，已有用户不受影响"),
            bool("auth.register.requireInvite", SettingGroup.AUTH, "注册需邀请码", "false", true,
                    "开启后注册必须填写有效邀请码"),
            number("auth.password.minLength", SettingGroup.AUTH, "密码最小长度", "8", false,
                    "注册与改密时的密码长度下限", 6, 64),

            // ---- 内容策略 ----
            number("content.post.maxLength", SettingGroup.CONTENT, "帖子正文上限", "20000", false,
                    "单篇帖子正文字符数上限", 100, 200000),
            number("content.comment.maxLength", SettingGroup.CONTENT, "评论长度上限", "1000", false,
                    "单条评论字符数上限", 50, 5000),
            number("content.report.autoHideThreshold", SettingGroup.CONTENT, "举报自动隐藏阈值", "5", false,
                    "同一内容累计被举报达到该数量后自动隐藏，0 表示不自动处理", 0, 100),
            bool("content.sensitiveWord.enabled", SettingGroup.CONTENT, "敏感词扫描", "true", false,
                    "开启后发帖/评论会按下方「敏感词处理方式」处理命中词"),
            option("content.sensitiveWord.mode", SettingGroup.CONTENT, "敏感词处理方式", "REVIEW", false,
                    "REVIEW=只记录命中不阻断；MASK=命中词替换为 *** 后入库；BLOCK=直接拒绝发布（422）",
                    List.of("REVIEW", "MASK", "BLOCK")),

            // ---- 互动与发现 ----
            number("interaction.feed.pageSize", SettingGroup.INTERACTION, "动态流每页条数", "20", false,
                    "全站动态流默认分页大小", 5, 50),
            number("interaction.hot.windowDays", SettingGroup.INTERACTION, "热门统计窗口（天）", "7", false,
                    "热门榜与推荐使用的统计时间窗口", 1, 30),

            // ---- 上传与媒体 ----
            number("media.image.maxMb", SettingGroup.MEDIA, "图片大小上限（MB）", "10", false,
                    "业务层校验值；Spring 请求体硬上限仍由环境变量控制", 1, 50),
            number("media.video.maxMb", SettingGroup.MEDIA, "视频大小上限（MB）", "200", false,
                    "业务层校验值；Spring 请求体硬上限仍由环境变量控制", 1, 500),

            // ---- AI 能力 ----
            number("ai.qa.dailyQuota", SettingGroup.AI, "每日提问配额", "200", false,
                    "单用户每日 AI 提问次数上限", 0, 10000),
            number("ai.qa.maxConcurrent", SettingGroup.AI, "并发提问上限", "1", false,
                    "单用户同时进行的流式提问数", 1, 5),
            bool("ai.assistant.schedulerEnabled", SettingGroup.AI, "频道助手自动任务", "true", false,
                    "关闭后仅保留手动试运行，定时任务不再执行"),
            number("ai.default.timeoutSeconds", SettingGroup.AI, "模型调用超时（秒）", "60", false,
                    "单次模型调用的默认超时，模型配置可覆盖", 5, 600),

            // ---- 运营开关 ----
            bool("ops.announcement.enabled", SettingGroup.OPS, "展示平台公告", "true", true,
                    "关闭后前台不展示公告条"),
            bool("ops.search.hotKeywordsEnabled", SettingGroup.OPS, "展示搜索热词", "true", true,
                    "关闭后搜索页不展示热词")
    );

    private static final Map<String, SettingDefinition> BY_KEY;

    static {
        Map<String, SettingDefinition> map = new LinkedHashMap<>();
        for (SettingDefinition definition : ALL) {
            if (map.putIfAbsent(definition.key(), definition) != null) {
                throw new IllegalStateException("配置键重复定义: " + definition.key());
            }
        }
        BY_KEY = Map.copyOf(map);
    }

    private SettingDefinitions() {
    }

    public static List<SettingDefinition> all() {
        return ALL;
    }

    public static SettingDefinition require(String key) {
        SettingDefinition definition = BY_KEY.get(key);
        if (definition == null) {
            throw new AppException(ErrorCode.SETTING_NOT_FOUND, "配置项不存在: " + key);
        }
        return definition;
    }

    public static boolean exists(String key) {
        return BY_KEY.containsKey(key);
    }

    // ------------------------------------------------------------------
    // 定义工厂：统一长度与范围约束，避免每条定义重复写边界值
    // ------------------------------------------------------------------

    private static SettingDefinition text(String key, SettingGroup group, String label, String defaultValue,
                                          boolean publicVisible, String description, int minLength, int maxLength) {
        return new SettingDefinition(key, group.key(), label, SettingType.STRING, defaultValue, publicVisible,
                description, minLength, Math.min(maxLength, MAX_TEXT_LENGTH), List.of());
    }

    private static SettingDefinition number(String key, SettingGroup group, String label, String defaultValue,
                                            boolean publicVisible, String description, int min, int max) {
        return new SettingDefinition(key, group.key(), label, SettingType.INT, defaultValue, publicVisible,
                description, min, max, List.of());
    }

    /**
     * 枚举型文本配置：{@code type=STRING} + {@code allowedValues} 白名单，后台渲染为下拉框。
     */
    private static SettingDefinition option(String key, SettingGroup group, String label, String defaultValue,
                                            boolean publicVisible, String description, List<String> allowedValues) {
        return new SettingDefinition(key, group.key(), label, SettingType.STRING, defaultValue, publicVisible,
                description, null, null, allowedValues);
    }

    private static SettingDefinition bool(String key, SettingGroup group, String label, String defaultValue,
                                          boolean publicVisible, String description) {
        return new SettingDefinition(key, group.key(), label, SettingType.BOOL, defaultValue, publicVisible,
                description, null, null, List.of("true", "false"));
    }
}
