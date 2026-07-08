/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalPipeline;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationTaskDO;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.ConversationWorkingMemoryService;
import com.nageoffer.ai.ragent.rag.service.ConversationTaskTurnService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;

/**
 * 流式对话流水线
 * <p>
 * 承载从 RAGChatServiceImpl 提取的业务编排逻辑：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 系统响应 / 检索 -> Prompt 组装 -> 流式输出
 * <p>
 * 流水线模式：通过私有方法 + boolean 返回值（handleXxx 返回 true 表示已处理并短路）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamChatPipeline {

    private final ConversationMemoryService memoryService;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalPipeline retrievalPipeline;
    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final StreamTaskManager taskManager;
    private final ConversationWorkingMemoryService workingMemoryService;
    private final ConversationTaskTurnService conversationTaskTurnService;

    /**
     * 执行流式对话管道
     */
    public void execute(StreamChatContext ctx) {
        // 加载上下文对话历史
        loadMemory(ctx);
        loadWorkingMemory(ctx);
        // 重写用户问题 调用大模型  chat
        rewriteQuery(ctx);
        // 意图识别调用大模型  chat
        resolveIntents(ctx);
        // 判断是否有歧义意图
        if (handleGuidance(ctx)) {
            return;
        }
        // 判断是否是系统交互类问题  调用大模型
        if (handleSystemOnly(ctx)) {
            // 如果是系统交互类问题，直接返回系统响应
            return;
        }
        // 检索知识库相关文档
        RetrievalContext retrievalCtx = retrieve(ctx);
        if (handleEmptyRetrieval(ctx, retrievalCtx)) {
            return;
        }

        streamRagResponse(ctx, retrievalCtx);
    }

    // ==================== 流水线阶段 ====================
    // 加载上下文对话历史
    private void loadMemory(StreamChatContext ctx) {
        List<ChatMessage> history = memoryService.load(ctx.getConversationId(), ctx.getUserId());
        String userMessageId = memoryService.append(ctx.getConversationId(), ctx.getUserId(), ChatMessage.user(ctx.getQuestion()));
        ctx.setHistory(history);
        ctx.setUserMessageId(userMessageId);
    }
    // 加载工作记忆上下文
    private void loadWorkingMemory(StreamChatContext ctx) {
        ConversationTaskDO conversationTask = workingMemoryService.resolveConversationTask(
                ctx.getConversationId(),
                ctx.getUserId(),
                ctx.getQuestion()
        );
        if (conversationTask == null || StrUtil.isBlank(conversationTask.getConversationTaskId())) {
            return;
        }

        String conversationTaskId = conversationTask.getConversationTaskId();
        ctx.setConversationTaskId(conversationTaskId);

        String taskTurnId = workingMemoryService.saveConversationTaskTurn(
                conversationTaskId,
                ctx.getConversationId(),
                ctx.getUserId(),
                ctx.getUserMessageId(),
                ctx.getQuestion(),
                null
        );
        ctx.setTaskTurnId(taskTurnId);

        List<ConversationMessageVO> taskMessages = workingMemoryService.loadConversationTaskContext(
                conversationTaskId,
                ctx.getConversationId(),
                ctx.getUserId(),
                6
        );
        List<ChatMessage> taskHistory = toChatMessages(taskMessages, ctx.getUserMessageId());
        if (CollUtil.isNotEmpty(taskHistory)) {
            ctx.setHistory(taskHistory);
        }
    }
    // 把用户问题和上下文对话历史，调用大模型重写，返回重写后的主问题和子问题
    private void rewriteQuery(StreamChatContext ctx) {
        // 返回调用大模型重写之后的结果，包含重写后的主问题和子问题
        // RewriteResult(
        //    rewrittenQuestion = "StreamCallback 和 SSE 有什么区别，以及项目中如何停止流式生成？",
        //    subQuestions = List.of(
        //        "StreamCallback 和 SSE 有什么区别？",
        //        "项目中如何停止流式生成？"
        //    )
        //)
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(ctx.getQuestion(), ctx.getHistory());
        ctx.setRewriteResult(rewriteResult);
        if (StrUtil.isNotBlank(ctx.getTaskTurnId())) {
            conversationTaskTurnService.updateRewriteResult(ctx.getTaskTurnId(), rewriteResult.rewrittenQuestion());
        }
    }
    // 意图识别，根据重写后的主问题和子问题，判断用户问题属于什么类型
    private void resolveIntents(StreamChatContext ctx) {
        // 根据改写结果判断用户问题属于什么类型
        List<SubQuestionIntent> subIntents = intentResolver.resolve(ctx.getRewriteResult());
        // 把所有子问题的意图识别结果，放入到上下文中，方便后续使用
        ctx.setSubIntents(subIntents);
    }
    // 判断是否有歧义意图
    private boolean handleGuidance(StreamChatContext ctx) {
        GuidanceDecision decision = guidanceService.detectAmbiguity(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getSubIntents()
        );
        if (!decision.isPrompt()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        // 向用户输出引导式问答提示
        callback.onContent(decision.getPrompt());
        // 标记引导式问答提示结束
        callback.onComplete();
        return true;
    }
    // 判断是否是系统交互类问题
    private boolean handleSystemOnly(StreamChatContext ctx) {
        // 取出意图识别结果中的所有节点
        List<SubQuestionIntent> subIntents = ctx.getSubIntents();
        boolean allSystemOnly = subIntents.stream()
                // 所有的子问题都只有一个意图节点，且是系统交互节点，就为true
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        // 如果不是纯 SYSTEM 问题，就放行
        if (!allSystemOnly) {
            return false;
        }
        // 如果是纯 SYSTEM 问题，取自定义 Prompt
        String customPrompt = subIntents.stream()
                // 获取所有的意图节点
                .flatMap(si -> si.nodeScores().stream())
                // 取出每个节点上的 promptTemplate。
                .map(ns -> ns.getNode().getPromptTemplate())
                .filter(StrUtil::isNotBlank)
                // 只使用第一个节点的prompt作为总体的prompt
                .findFirst()
                .orElse(null);
        // 调用大模型发出请求
        StreamCancellationHandle handle = streamSystemResponse(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getHistory(),
                customPrompt,
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle);
        return true;
    }
    // 检索知识库相关文档
    private RetrievalContext retrieve(StreamChatContext ctx) {
        return retrievalPipeline.retrieve(ctx.getSubIntents(), DEFAULT_TOP_K);
    }
    // 如果检索到的文档为空，就返回空文档
    private boolean handleEmptyRetrieval(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        if (!retrievalCtx.isEmpty()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent("未检索到与问题相关的文档内容。");
        callback.onComplete();
        return true;
    }
    // 流式生成 RAG 响应
    /**
     * 将任务级消息视图转换为大模型上下文消息。
     *
     * @param messages         任务级消息视图
     * @param excludeMessageId 需要排除的消息ID
     * @return 大模型上下文消息
     */
    private List<ChatMessage> toChatMessages(List<ConversationMessageVO> messages, String excludeMessageId) {
        if (CollUtil.isEmpty(messages)) {
            return List.of();
        }
        return messages.stream()
                .filter(message -> message != null && StrUtil.isNotBlank(message.getContent()))
                .filter(message -> !StrUtil.equals(message.getId(), excludeMessageId))
                .map(this::toChatMessage)
                .filter(message -> message != null && StrUtil.isNotBlank(message.getContent()))
                .toList();
    }

    /**
     * 将一条任务级消息视图转换为大模型上下文消息。
     *
     * @param message 任务级消息视图
     * @return 大模型上下文消息
     */
    private ChatMessage toChatMessage(ConversationMessageVO message) {
        ChatMessage.Role role = ChatMessage.Role.fromString(message.getRole());
        if (role != ChatMessage.Role.USER && role != ChatMessage.Role.ASSISTANT) {
            return null;
        }
        return new ChatMessage(role, message.getContent());
    }

    private void streamRagResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        // 聚合所有意图用于 prompt 规划
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(ctx.getSubIntents());

        StreamCancellationHandle handle = streamLLMResponse(
                ctx.getRewriteResult(),
                retrievalCtx,
                mergedGroup,
                ctx.getHistory(),
                ctx.isDeepThinking(),
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle);
    }

    // ==================== LLM 响应 ====================
    // 用户闲聊意图的流式响应
    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        // 如果 SYSTEM 节点自己配置了 prompt，就用节点自己的 prompt；否则用默认系统问答 prompt。
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        // 构建 prompt 上下文
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }
}
