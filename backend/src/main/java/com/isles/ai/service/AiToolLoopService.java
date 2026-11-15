package com.isles.ai.service;

import com.isles.ai.dto.AiContextPost;
import com.isles.ai.provider.AiChatMessage;
import com.isles.ai.provider.AiChatRequest;
import com.isles.ai.provider.AiChatResult;
import com.isles.ai.provider.AiProvider;
import com.isles.ai.provider.AiProviderException;
import com.isles.ai.provider.AiToolCall;
import com.isles.ai.provider.AiToolDefinition;
import com.isles.ai.provider.AiUsage;
import com.isles.ai.tool.AiToolRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 工具调用循环（docs/05 §2.13）：模型要工具 → 执行 → 回填 tool result → 再请求，最多 2 轮。
 *
 * <p>循环本身与传输方式解耦：{@link TurnRunner} 决定这一轮是阻塞调用（{@link #blockingTurn}）
 * 还是流式调用（由 {@code AiChatService} 注入，边收边推 SSE）。两条路径共享同一套轮次、超时、
 * 中断与来源命中逻辑，避免行为漂移。
 *
 * <p>约束：
 * <ul>
 *   <li>工具轮次计入总超时：每轮按剩余时间下发 {@code timeoutSeconds}，超时抛 TIMEOUT；</li>
 *   <li>用户停止（{@code cancelled}）时立即中断，不再执行剩余工具；</li>
 *   <li>最多 2 轮工具调用，之后强制不带工具再请求一次，保证一定能收敛到自然语言回答。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AiToolLoopService {

    private static final Logger log = LoggerFactory.getLogger(AiToolLoopService.class);

    /** 最多 2 轮工具调用（第 3 次请求不再提供工具）。 */
    public static final int MAX_TOOL_ROUNDS = 2;

    private final AiToolRegistry toolRegistry;

    /** 一轮模型调用结果（阻塞/流式统一结构）。 */
    public record ToolTurn(String content, String reasoning, AiUsage usage, List<AiToolCall> toolCalls) {

        public ToolTurn {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }

    /** 单轮调用执行器：流式实现需要把 delta 推给 SSE。 */
    @FunctionalInterface
    public interface TurnRunner {
        ToolTurn run(AiChatRequest request);
    }

    /**
     * 循环结果。
     *
     * @param hits       工具实际命中的帖子（已去重），用于生成来源引用
     * @param toolRounds 实际执行的工具轮次（0 表示模型未调用工具）
     */
    public record ToolLoopResult(String content, String reasoning, AiUsage usage, List<AiContextPost> hits,
                                 int toolRounds) {
    }

    /**
     * 执行工具循环。
     *
     * @param base         基础请求（含模型、消息、思考参数等；tools 字段被本方法覆盖）
     * @param definitions  可用工具；空列表等价于不启用工具调用
     * @param channelId    频道会话的频道 id（全站会话为 null）
     * @param deadlineMillis 本次回答的总超时截止时间（epoch millis）
     * @param cancelled    用户是否已请求停止
     * @param runner       单轮执行器
     */
    public ToolLoopResult run(AiChatRequest base, List<AiToolDefinition> definitions, String channelId,
                              long deadlineMillis, BooleanSupplier cancelled, TurnRunner runner) {
        List<AiToolDefinition> tools = definitions == null ? List.of() : definitions;
        List<AiChatMessage> messages = new ArrayList<>(base.messages());
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        AiUsage usage = AiUsage.empty();
        List<AiContextPost> hits = List.of();
        int toolRounds = 0;

        while (true) {
            boolean toolsAllowed = !tools.isEmpty() && toolRounds < MAX_TOOL_ROUNDS;
            AiChatRequest request = base.withMessages(messages)
                    .withTools(toolsAllowed ? tools : List.of())
                    .withTimeoutSeconds(remainingSeconds(deadlineMillis));
            ToolTurn turn = runner.run(request);
            content.append(nullToEmpty(turn.content()));
            reasoning.append(nullToEmpty(turn.reasoning()));
            usage = usage.plus(turn.usage());

            if (turn.toolCalls().isEmpty() || !toolsAllowed) {
                break;
            }
            if (cancelled.getAsBoolean()) {
                log.info("用户在工具轮次中停止生成，跳过剩余工具执行 toolRounds={}", toolRounds);
                break;
            }
            toolRounds++;
            messages.add(AiChatMessage.assistantToolCalls(turn.content(), turn.toolCalls()));
            for (AiToolCall call : turn.toolCalls()) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                AiToolRegistry.ToolOutcome outcome =
                        toolRegistry.execute(call.name(), call.arguments(), channelId);
                hits = AiToolRegistry.ToolOutcome.merge(hits, outcome.posts());
                messages.add(AiChatMessage.toolResult(call.id(), outcome.content()));
            }
            if (cancelled.getAsBoolean()) {
                break;
            }
            log.debug("工具轮次完成 round={} calls={} hits={}", toolRounds, turn.toolCalls().size(), hits.size());
        }
        return new ToolLoopResult(content.toString(), reasoning.toString(), usage, List.copyOf(hits), toolRounds);
    }

    /**
     * 阻塞式执行器：非流式调用方（以及单测）使用。
     */
    public TurnRunner blockingTurn(AiProvider provider) {
        return request -> {
            AiChatResult result = provider.chat(request);
            return new ToolTurn(result.content(), result.reasoningContent(), result.usage(), result.toolCalls());
        };
    }

    private Integer remainingSeconds(long deadlineMillis) {
        long remainingMillis = deadlineMillis - System.currentTimeMillis();
        if (remainingMillis <= 0) {
            throw new AiProviderException(AiProviderException.TIMEOUT, "AI 回答超时，请稍后重试");
        }
        // 至少 1 秒，避免整数截断成 0 被当成「使用默认超时」
        return (int) Math.max(1, remainingMillis / 1000);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
