package com.isles.agent.controller;

import com.isles.agent.dto.AgentDtos;
import com.isles.agent.service.AgentProfileService;
import com.isles.agent.service.AgentRuntime;
import com.isles.common.pagination.PageResult;
import com.isles.common.security.CurrentUserProvider;
import com.isles.platform.permission.PlatformPermission;
import com.isles.platform.permission.RequirePlatformPermission;
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

import java.util.Map;

/**
 * 平台后台 AI 用户代理管理（V13 / docs/05 §2.15、docs/12 §7）。
 *
 * <p>权限点 {@code AGENT_MANAGE}（超管与运营默认拥有）。所有写操作写平台审计日志。
 *
 * <h2>两个 id 的语义（务必区分，测试同学踩过）</h2>
 * <ul>
 *   <li><b>路径参数 {@code {id}}</b>：一律是<b>代理账号 id</b>（{@code users.id}）。创建接口返回的
 *       {@code userId} 就是它；{@code run-now} / {@code pause} / {@code resume} / {@code runs} 全部按它定位。</li>
 *   <li><b>{@link AgentDtos.AgentView#id()}</b>：<b>人设行 id</b>（{@code agent_profiles.id}），
 *       只标识人设这一行，<b>不能</b>当作路径参数回传（拿它调接口会 404：人设 id 查不到账号）。</li>
 * </ul>
 * 前端列表请统一用 {@code AgentView.userId} 作为操作主键。
 */
@RestController
@RequestMapping("/api/v1/admin/agents")
@RequiredArgsConstructor
public class AgentAdminController {

    private final AgentProfileService profileService;
    private final AgentRuntime runtime;
    private final CurrentUserProvider currentUserProvider;

    /** 代理列表（含今日产出与最近运行状态）。 */
    @GetMapping
    @RequirePlatformPermission(PlatformPermission.AGENT_MANAGE)
    public PageResult<AgentDtos.AgentView> list() {
        return new PageResult<>(profileService.list(), null);
    }

    /** 平台级开关状态（kill switch 与限额），供后台页头展示。 */
    @GetMapping("/switches")
    @RequirePlatformPermission(PlatformPermission.AGENT_MANAGE)
    public Map<String, Object> switches() {
        return profileService.switches();
    }

    @GetMapping("/{id}")
    @RequirePlatformPermission(PlatformPermission.AGENT_MANAGE)
    public AgentDtos.AgentView detail(@PathVariable String id) {
        return profileService.detail(id);
    }

    /** 创建代理：同时创建 {@code is_agent=1} 的账号（用户名可空，自动生成 agent_xxxxxxxx）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePlatformPermission(PlatformPermission.AGENT_MANAGE)
    public AgentDtos.AgentView create(@Valid @RequestBody AgentDtos.SaveAgentRequest request) {
        return profileService.create(request, currentUserProvider.requireUserId());
    }

    @PutMapping("/{id}")
    @RequirePlatformPermission(PlatformPermission.AGENT_MANAGE)
    public AgentDtos.AgentView update(@PathVariable String id,
                                      @Valid @RequestBody AgentDtos.SaveAgentRequest request) {
        return profileService.update(id, request, currentUserProvider.requireUserId());
    }

    /** 删除代理：人设删除、账号置 DISABLED，已产出的内容保留。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePlatformPermission(PlatformPermission.AGENT_MANAGE)
    public void delete(@PathVariable String id) {
        profileService.delete(id, currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/pause")
    @RequirePlatformPermission(PlatformPermission.AGENT_MANAGE)
    public AgentDtos.AgentView pause(@PathVariable String id) {
        return profileService.pause(id, currentUserProvider.requireUserId());
    }

    @PostMapping("/{id}/resume")
    @RequirePlatformPermission(PlatformPermission.AGENT_MANAGE)
    public AgentDtos.AgentView resume(@PathVariable String id) {
        return profileService.resume(id, currentUserProvider.requireUserId());
    }

    /**
     * 手动触发一次运行（测试必需）：忽略活跃时段与概率，其余闸门照旧。
     */
    @PostMapping("/{id}/run-now")
    @RequirePlatformPermission(PlatformPermission.AGENT_MANAGE)
    public AgentDtos.RunNowResponse runNow(@PathVariable String id) {
        return runtime.runNow(id, currentUserProvider.requireUserId());
    }

    /** 运行日志（可回放 planJson / toolCallsJson）。 */
    @GetMapping("/{id}/runs")
    @RequirePlatformPermission(PlatformPermission.AGENT_MANAGE)
    public AgentDtos.RunPage runs(@PathVariable String id,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        return runtime.listRuns(id, page, size);
    }
}
