package com.isles.ai.service;

import com.isles.ai.config.AiProperties;
import com.isles.ai.dto.AiRequests;
import com.isles.ai.dto.AiResponses;
import com.isles.ai.entity.AiConversation;
import com.isles.ai.entity.AiMessage;
import com.isles.ai.entity.AiModelConfig;
import com.isles.ai.entity.ChannelAiSetting;
import com.isles.ai.mapper.ChannelAiSettingMapper;
import com.isles.ai.dto.AiContextPost;
import com.isles.ai.provider.AiChatMessage;
import com.isles.ai.provider.AiChatRequest;
import com.isles.ai.provider.AiProvider;
import com.isles.ai.provider.AiProviderException;
import com.isles.ai.provider.AiStreamListener;
import com.isles.ai.provider.AiToolCall;
import com.isles.ai.provider.AiToolDefinition;
import com.isles.ai.provider.AiUsage;
import com.isles.ai.tool.AiToolRegistry;
import com.isles.channel.entity.Channel;
import com.isles.channel.mapper.ChannelMapper;
import com.isles.channel.service.ChannelAccessService;
import com.isles.common.error.AppException;
import com.isles.common.error.ErrorCode;
import com.isles.common.id.UlidGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 频道问问对话编排（docs/03 FR-AIQA-01/03/04/05/07/08/09、§6.5）。
 *
 * <p>流程：校验会话/频道可见性 → 解析模型 → 配额与并发检查 → 持久化提问 → 检索上下文拼装提示词 →
 * 落库助手占位消息 → 异步调用 Provider 流式生成 → SSE 推送 delta/reasoning → 结束后落库。
 *
 * <p>并发与线程：SSE 请求线程只做同步校验与 emitter 创建；阻塞的模型调用在
 * {@code aiStreamExecutor} 线程池执行，单用户同时只允许一个生成（docs/07 §7「并发 1」）。
 */
@Service
public class AiChatService {

    private static final Logger log = LoggerFactory.getLogger(AiChatService.class);

    private static final String EVENT_META = "meta";
    private static final String EVENT_DELTA = "delta";
    private static final String EVENT_REASONING = "reasoning";
    private static final String EVENT_DONE = "done";
    private static final String EVENT_ERROR = "error";
    /** 来源引用事件：每次回答都会下发（0 命中发空数组），前端以此为准 */
    private static final String EVENT_SOURCES = "sources";
    private static final int FALLBACK_SOURCE_LIMIT = 4;
    private static final int ESTIMATED_CHARS_PER_TOKEN = 4;
    private static final int MAX_IMAGES = 4;

    private final AiConversationService conversationService;
    private final AiModelConfigService modelConfigService;
    private final AiContextService contextService;
    private final AiToolLoopService toolLoopService;
    private final AiToolRegistry toolRegistry;
    private final AiImageResolver imageResolver;
    private final AiStreamRegistry streamRegistry;
    private final ChannelAccessService accessService;
    private final ChannelMapper channelMapper;
    private final ChannelAiSettingMapper channelAiSettingMapper;
    private final AiProperties properties;
    private final ThreadPoolTaskExecutor streamExecutor;

    /** 单用户进行中的回答：userId → 助手消息 id。 */
    private final Map<String, String> activeStreams = new ConcurrentHashMap<>();

    public AiChatService(AiConversationService conversationService,
                         AiModelConfigService modelConfigService,
                         AiContextService contextService,
                         AiToolLoopService toolLoopService,
                         AiToolRegistry toolRegistry,
                         AiImageResolver imageResolver,
                         AiStreamRegistry streamRegistry,
                         ChannelAccessService accessService,
                         ChannelMapper channelMapper,
                         ChannelAiSettingMapper channelAiSettingMapper,
                         AiProperties properties,
                         @Qualifier("aiStreamExecutor") ThreadPoolTaskExecutor streamExecutor) {
        this.conversationService = conversationService;
        this.modelConfigService = modelConfigService;
        this.contextService = contextService;
        this.toolLoopService = toolLoopService;
        this.toolRegistry = toolRegistry;
        this.imageResolver = imageResolver;
        this.streamRegistry = streamRegistry;
        this.accessService = accessService;
        this.channelMapper = channelMapper;
        this.channelAiSettingMapper = channelAiSettingMapper;
        this.properties = properties;
        this.streamExecutor = streamExecutor;
    }

    // ---------------------------------------------------------------------
    // 对外入口
    // ---------------------------------------------------------------------

    /**
     * 发送提问（流式）。{@code request.regenerate=true} 等价于对最后一条提问重新生成。
     */
    public SseEmitter streamSend(String conversationId, AiRequests.SendMessageRequest request, String userId) {
        AiConversation conversation = conversationService.requireOwnedConversation(conversationId, userId);
        validateChannelScope(conversation, userId);
        AiModelConfig model = modelConfigService.resolveForChat(
                blankToNull(request.modelConfigId()), conversation.getModelConfigId());

        List<AiMessage> history;
        String question;
        List<String> images;
        if (request.regenerate()) {
            AiMessage lastUserMessage = conversationService.findLastUserMessage(conversationId);
            if (lastUserMessage == null) {
                throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "当前会话还没有可重新生成的提问");
            }
            conversationService.deleteMessagesAfter(conversationId, lastUserMessage.getId());
            history = historyBefore(conversationId, lastUserMessage.getId());
            question = lastUserMessage.getContent();
            images = conversationService.parseImages(lastUserMessage.getImages());
        } else {
            history = conversationService.recentHistory(conversationId, properties.historyMaxMessages());
            question = request.content().trim();
            images = request.imageUrls() == null ? List.of() : request.imageUrls();
            enforceQuota(userId);
            conversationService.appendUserMessage(conversationId, userId, question, images);
        }
        return startStream(conversation, userId, model, question, images, history, request.deepThinking());
    }

    /**
     * 重新生成最后一条回答。
     */
    public SseEmitter streamRegenerate(String conversationId, AiRequests.RegenerateRequest request, String userId) {
        AiConversation conversation = conversationService.requireOwnedConversation(conversationId, userId);
        validateChannelScope(conversation, userId);
        AiModelConfig model = modelConfigService.resolveForChat(
                blankToNull(request.modelConfigId()), conversation.getModelConfigId());
        AiMessage lastUserMessage = conversationService.findLastUserMessage(conversationId);
        if (lastUserMessage == null) {
            throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "当前会话还没有可重新生成的提问");
        }
        conversationService.deleteMessagesAfter(conversationId, lastUserMessage.getId());
        List<AiMessage> history = historyBefore(conversationId, lastUserMessage.getId());
        return startStream(conversation, userId, model, lastUserMessage.getContent(),
                conversationService.parseImages(lastUserMessage.getImages()), history, request.deepThinking());
    }

    /**
     * 编辑历史提问并重发：截断该提问之后的所有消息，再基于新提问生成回答。
     */
    public SseEmitter streamEdit(String messageId, AiRequests.EditMessageRequest request, String userId) {
        AiMessage message = conversationService.requireUserMessageOwned(messageId, userId);
        AiConversation conversation = conversationService.requireOwnedConversation(message.getConversationId(), userId);
        validateChannelScope(conversation, userId);
        AiModelConfig model = modelConfigService.resolveForChat(
                blankToNull(request.modelConfigId()), conversation.getModelConfigId());
        conversationService.editUserMessage(messageId, userId, request.content());
        List<AiMessage> history = historyBefore(conversation.getId(), messageId);
        return startStream(conversation, userId, model, request.content().trim(),
                conversationService.parseImages(message.getImages()), history, request.deepThinking());
    }

    /**
     * 停止生成：置位取消标记，Provider 在下个 chunk 前结束；未在生成中返回 stopped=false。
     */
    public AiResponses.StopResponse stop(String messageId) {
        return new AiResponses.StopResponse(streamRegistry.cancel(messageId));
    }

    // ---------------------------------------------------------------------
    // 流式执行
    // ---------------------------------------------------------------------

    private SseEmitter startStream(AiConversation conversation, String userId, AiModelConfig model, String question,
                                   List<String> images, List<AiMessage> history, boolean deepThinking) {
        List<String> resolvedImages = imageResolver.resolveAll(images);
        if (!resolvedImages.isEmpty() && !Boolean.TRUE.equals(model.getVisionEnabled())) {
            // 模型不支持视觉时降级为纯文本（docs/03 FR-AIQA-04），前端在 meta 中能感知来源不变
            log.info("模型未开启视觉能力，图片降级为纯文本 modelConfigId={}", model.getId());
            resolvedImages = List.of();
        }
        AiContextService.AiPromptRequest promptRequest = new AiContextService.AiPromptRequest(
                conversation.getScope(),
                conversation.getChannelId(),
                channelName(conversation),
                question,
                resolvedImages,
                history,
                deepThinking,
                assistantPersona(conversation));
        // 工具调用模式：不预置检索结果，由模型主动检索；其余模型保持原有关键词检索
        boolean toolMode = Boolean.TRUE.equals(model.getToolCallEnabled());
        AiContextService.AiPrompt prompt = toolMode
                ? contextService.buildToolPrompt(promptRequest)
                : contextService.buildPrompt(promptRequest);
        List<AiResponses.SourceResponse> initialSources = prompt.sources();
        if (toolMode && initialSources.isEmpty()) {
            // meta 事件早于工具执行：先给一份关键词兜底引用，工具跑完后用 sources 事件覆盖为真实命中
            initialSources = contextService.toSources(contextService.retrieve(question,
                    channelScoped(conversation) ? conversation.getChannelId() : null, FALLBACK_SOURCE_LIMIT));
        }
        String sourcesJson = conversationService.toSourcesJson(initialSources);

        // 先落库占位消息：meta 事件用同一 id，前端停止/刷新都能定位
        AiMessage placeholder = conversationService.createAssistantPlaceholder(
                conversation.getId(), userId, sourcesJson, model.getModel(), model.getId());
        try {
            acquireStreamSlot(userId, placeholder.getId());
        } catch (RuntimeException ex) {
            failPlaceholder(new StreamTask(conversation.getId(), userId, model, prompt, sourcesJson,
                    placeholder.getId(), deepThinking, promptRequest, toolMode, channelScoped(conversation),
                    conversation.getChannelId(), initialSources), AiMessage.Status.FAILED, "");
            throw ex;
        }
        streamRegistry.register(placeholder.getId());

        SseEmitter emitter = new SseEmitter(Math.max(10, properties.streamTimeoutSeconds()) * 1000L);
        StreamTask task = new StreamTask(conversation.getId(), userId, model, prompt, sourcesJson,
                placeholder.getId(), deepThinking, promptRequest, toolMode, channelScoped(conversation),
                conversation.getChannelId(), initialSources);
        emitter.onTimeout(() -> streamRegistry.cancel(placeholder.getId()));
        emitter.onError(ex -> streamRegistry.cancel(placeholder.getId()));
        try {
            streamExecutor.execute(() -> runStream(emitter, task));
        } catch (RejectedExecutionException ex) {
            releaseStreamSlot(userId, placeholder.getId());
            streamRegistry.remove(placeholder.getId());
            failPlaceholder(task, AiMessage.Status.FAILED, "");
            throw new AppException(ErrorCode.RATE_LIMITED, "AI 服务繁忙，请稍后再试");
        }
        return emitter;
    }

    private void runStream(SseEmitter emitter, StreamTask task) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        try {
            sendEvent(emitter, EVENT_META, new AiResponses.StreamMeta(task.conversationId(), task.messageId(),
                    task.model().getModel(), task.initialSources(), task.deepThinking()));
            AiProvider provider = modelConfigService.providerFor(task.model());
            AiChatRequest baseRequest = AiChatRequest.of(task.model().getModel(), task.prompt().messages(),
                    true, task.deepThinking(), task.model().getMaxTokens() == null ? 0 : task.model().getMaxTokens(),
                    Boolean.TRUE.equals(task.model().getReasoningEnabled()), task.model().getThinkingMode());
            List<AiToolDefinition> tools = task.toolMode()
                    ? toolRegistry.definitions(task.channelScope())
                    : List.of();
            // 工具轮次计入总超时：循环按剩余时间下发每轮 timeoutSeconds
            long deadline = System.currentTimeMillis()
                    + Math.max(10, properties.streamTimeoutSeconds()) * 1000L;
            // 降级时用关键词检索结果替换初始来源（meta 已下发，sources 事件会覆盖）
            List<AiResponses.SourceResponse> effectiveSources = task.initialSources();
            AiToolLoopService.ToolLoopResult loopResult;
            try {
                loopResult = toolLoopService.run(baseRequest, tools, task.channelId(),
                        deadline, () -> streamRegistry.isCancelled(task.messageId()),
                        request -> streamRound(provider, request, emitter, task, content, reasoning));
            } catch (AiProviderException ex) {
                // 模型不支持 tools 时上游常直接报 400：降级为关键词检索再答一次，功能不能坏
                if (!task.toolMode() || content.length() > 0) {
                    throw ex;
                }
                log.warn("模型工具调用失败，降级为关键词检索 modelConfigId={} code={} message={}",
                        task.model().getId(), ex.getCode(), ex.getMessage());
                AiContextService.AiPrompt fallback = contextService.buildPrompt(task.promptRequest());
                loopResult = toolLoopService.run(baseRequest.withMessages(fallback.messages()), List.of(),
                        task.channelId(), deadline, () -> streamRegistry.isCancelled(task.messageId()),
                        request -> streamRound(provider, request, emitter, task, content, reasoning));
                effectiveSources = fallback.sources();
            }
            boolean aborted = streamRegistry.isCancelled(task.messageId());
            AiMessage.Status status = aborted ? AiMessage.Status.ABORTED : AiMessage.Status.COMPLETED;
            // 来源引用：工具实际命中的帖子优先；未命中或未开启工具时沿用关键词检索结果
            List<AiResponses.SourceResponse> sources = loopResult.hits().isEmpty()
                    ? effectiveSources
                    : contextService.toSources(loopResult.hits());
            // 无条件下发：前端以本事件为「引用可展示」的唯一信号（0 命中也要发空数组），
            // 非工具模式同样发（sources 即关键词检索结果），避免前端一直等不到事件。
            sendEventQuietly(emitter, EVENT_SOURCES,
                    new AiResponses.StreamSources(sources, loopResult.toolRounds()));
            int tokenIn = estimateTokens(task.prompt().messages());
            int tokenOut = estimateTokens(content.toString());
            conversationService.finishAssistantMessage(task.messageId(), content.toString(), reasoning.toString(),
                    conversationService.toSourcesJson(sources), task.model().getModel(), task.model().getId(),
                    tokenIn, tokenOut, status);
            finished.set(true);
            sendEventQuietly(emitter, EVENT_DONE, new AiResponses.StreamDone(task.messageId(), aborted, status.name(),
                    tokenIn, tokenOut));
            completeQuietly(emitter);
        } catch (ClientDisconnectedException ex) {
            // 客户端断开：已生成的部分保留为 ABORTED
            log.debug("AI 流式连接已断开 conversationId={}", task.conversationId());
            persistFailure(task, content.toString(), reasoning.toString(), AiMessage.Status.ABORTED);
            finished.set(true);
            completeQuietly(emitter);
        } catch (AiProviderException ex) {
            log.warn("AI 流式生成失败 conversationId={} code={} message={}",
                    task.conversationId(), ex.getCode(), ex.getMessage());
            persistFailure(task, content.toString(), reasoning.toString(), AiMessage.Status.FAILED);
            finished.set(true);
            sendEventQuietly(emitter, EVENT_ERROR, new AiResponses.StreamError(ex.getCode(), ex.getMessage()));
            sendEventQuietly(emitter, EVENT_DONE,
                    new AiResponses.StreamDone(task.messageId(), false, AiMessage.Status.FAILED.name(), null, null));
            completeQuietly(emitter);
        } catch (RuntimeException ex) {
            log.error("AI 流式生成异常 conversationId={}", task.conversationId(), ex);
            persistFailure(task, content.toString(), reasoning.toString(), AiMessage.Status.FAILED);
            finished.set(true);
            sendEventQuietly(emitter, EVENT_ERROR,
                    new AiResponses.StreamError(AiProviderException.INTERNAL_ERROR, "AI 生成失败，请稍后重试"));
            sendEventQuietly(emitter, EVENT_DONE,
                    new AiResponses.StreamDone(task.messageId(), false, AiMessage.Status.FAILED.name(), null, null));
            completeQuietly(emitter);
        } finally {
            if (!finished.get()) {
                // 兜底（如线程被中断/超时）：把占位消息收敛到 ABORTED，避免长期停留在 STREAMING
                persistFailure(task, content.toString(), reasoning.toString(), AiMessage.Status.ABORTED);
            }
            streamRegistry.remove(task.messageId());
            releaseStreamSlot(task.userId(), task.messageId());
        }
    }

    /**
     * 执行一轮流式模型调用：delta 实时推给前端，同时收集本轮工具调用与用量。
     *
     * <p>工具轮次通常没有正文增量，因此「边收边推」不会把中间态暴露成最终答案；
     * 若模型在工具轮里也输出了文本，会作为回答的一部分保留（与上游行为一致）。
     */
    private AiToolLoopService.ToolTurn streamRound(AiProvider provider, AiChatRequest request, SseEmitter emitter,
                                                   StreamTask task, StringBuilder content, StringBuilder reasoning) {
        StringBuilder roundContent = new StringBuilder();
        StringBuilder roundReasoning = new StringBuilder();
        AtomicReference<List<AiToolCall>> toolCalls = new AtomicReference<>(List.of());
        AtomicReference<AiUsage> usage = new AtomicReference<>(AiUsage.empty());
        provider.streamChat(request, new AiStreamListener() {
            @Override
            public void onDelta(String delta) {
                content.append(delta);
                roundContent.append(delta);
                sendEventQuietly(emitter, EVENT_DELTA, new AiResponses.StreamDelta(delta));
            }

            @Override
            public void onReasoning(String delta) {
                reasoning.append(delta);
                roundReasoning.append(delta);
                sendEventQuietly(emitter, EVENT_REASONING, new AiResponses.StreamDelta(delta));
            }

            @Override
            public void onToolCalls(List<AiToolCall> calls, String finishReason) {
                toolCalls.set(calls);
            }

            @Override
            public void onCompleted(AiUsage roundUsage) {
                if (roundUsage != null) {
                    usage.set(roundUsage);
                }
            }

            @Override
            public boolean isCancelled() {
                return streamRegistry.isCancelled(task.messageId());
            }
        });
        return new AiToolLoopService.ToolTurn(roundContent.toString(), roundReasoning.toString(), usage.get(),
                toolCalls.get());
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ex) {
            log.debug("SSE 连接收尾失败（可能已关闭）");
        }
    }

    private void persistFailure(StreamTask task, String content, String reasoning, AiMessage.Status status) {
        try {
            conversationService.finishAssistantMessage(task.messageId(), content, reasoning, task.sourcesJson(),
                    task.model().getModel(), task.model().getId(), estimateTokens(task.prompt().messages()),
                    estimateTokens(content), status);
        } catch (RuntimeException ex) {
            log.error("AI 失败状态落库失败 messageId={}", task.messageId(), ex);
        }
    }

    private void failPlaceholder(StreamTask task, AiMessage.Status status, String content) {
        persistFailure(task, content, "", status);
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private void validateChannelScope(AiConversation conversation, String userId) {
        if (AiConversation.Scope.CHANNEL.name().equals(conversation.getScope())) {
            if (conversation.getChannelId() == null) {
                throw new AppException(ErrorCode.BUSINESS_RULE_VIOLATION, "频道会话缺少频道信息");
            }
            // 频道内问答仅对可见频道开放；仅成员可见频道由 AccessService 强制成员校验
            accessService.requireVisibleChannel(conversation.getChannelId(), userId);
        }
    }

    private void enforceQuota(String userId) {
        long used = conversationService.countTodayUserQuestions(userId);
        int quota = Math.max(1, properties.dailyQuota());
        if (used >= quota) {
            throw new AppException(ErrorCode.RATE_LIMITED, "今日 AI 提问已达上限（" + quota + " 次），请明天再试");
        }
    }

    private void acquireStreamSlot(String userId, String messageId) {
        String existing = activeStreams.putIfAbsent(userId, messageId);
        if (existing != null) {
            throw new AppException(ErrorCode.RATE_LIMITED, "已有正在生成的回答，请等待完成或停止后再试");
        }
        if (properties.maxConcurrent() > 1) {
            // v1 固定单并发；配置大于 1 时仍以单用户串行为准，避免流式资源失控
            log.debug("maxConcurrent 配置大于 1，v1 仍按单用户单并发执行");
        }
    }

    private void releaseStreamSlot(String userId, String messageId) {
        activeStreams.remove(userId, messageId);
    }

    private List<AiMessage> historyBefore(String conversationId, String messageId) {
        List<AiMessage> history = conversationService.recentHistory(conversationId, properties.historyMaxMessages());
        if (!history.isEmpty() && messageId.equals(history.get(history.size() - 1).getId())) {
            return history.subList(0, history.size() - 1);
        }
        return history;
    }

    private boolean channelScoped(AiConversation conversation) {
        return AiConversation.Scope.CHANNEL.name().equals(conversation.getScope())
                && conversation.getChannelId() != null;
    }

    private String channelName(AiConversation conversation) {
        if (!AiConversation.Scope.CHANNEL.name().equals(conversation.getScope())
                || conversation.getChannelId() == null) {
            return null;
        }
        Channel channel = channelMapper.selectById(conversation.getChannelId());
        return channel == null ? null : channel.getName();
    }

    private String assistantPersona(AiConversation conversation) {
        if (!AiConversation.Scope.CHANNEL.name().equals(conversation.getScope())
                || conversation.getChannelId() == null) {
            return null;
        }
        ChannelAiSetting setting = channelAiSettingMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ChannelAiSetting>lambdaQuery()
                        .eq(ChannelAiSetting::getChannelId, conversation.getChannelId())
                        .last("LIMIT 1"));
        return setting == null ? null : setting.getPersona();
    }

    private int estimateTokens(List<AiChatMessage> messages) {
        int chars = 0;
        for (AiChatMessage message : messages) {
            chars += message.content() == null ? 0 : message.content().length();
            chars += message.imageUrls().size() * 256;
        }
        return Math.max(1, chars / ESTIMATED_CHARS_PER_TOKEN);
    }

    private int estimateTokens(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        return Math.max(1, content.length() / ESTIMATED_CHARS_PER_TOKEN);
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            // 客户端断开：抛给上游停止生成（由 runStream 的 finally 标记 ABORTED）
            throw new ClientDisconnectedException(ex);
        }
    }

    private void sendEventQuietly(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
            // 已经断开时忽略推送失败；取消标记由 onError/onTimeout 回调设置
            log.debug("SSE 推送失败（客户端可能已断开） event={}", name);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 单次流式任务上下文。 */
    private record StreamTask(String conversationId,
                              String userId,
                              AiModelConfig model,
                              AiContextService.AiPrompt prompt,
                              String sourcesJson,
                              String messageId,
                              boolean deepThinking,
                              AiContextService.AiPromptRequest promptRequest,
                              boolean toolMode,
                              boolean channelScope,
                              String channelId,
                              List<AiResponses.SourceResponse> initialSources) {
    }

    /** 客户端断开连接：内部异常，仅用于中止读取循环。 */
    private static class ClientDisconnectedException extends RuntimeException {
        ClientDisconnectedException(Throwable cause) {
            super("客户端已断开", cause);
        }
    }
}
