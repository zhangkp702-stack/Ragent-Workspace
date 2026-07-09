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

package com.nageoffer.ai.ragent.rag.service.handler;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.ConversationTaskTurnService;
import com.nageoffer.ai.ragent.rag.service.ConversationWorkingMemoryService;

import java.util.Optional;

@Slf4j
public class StreamChatEventHandler implements StreamCallback {

    private static final String TYPE_THINK = "think";
    private static final String TYPE_RESPONSE = "response";

    private final SseEmitterSender sender;
    private final String conversationId;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final String taskId;
    private final String userId;
    private final StreamTaskManager taskManager;
    private final ConversationTaskTurnService conversationTaskTurnService;
    private final ConversationWorkingMemoryService conversationWorkingMemoryService;
    private String conversationTaskId;
    private String taskTurnId;
    private String questionText;
    private String rewriteQuestion;

    // 用于控制模型生成的 token 数量，避免一次生成过长的 token 导致性能问题
    private final int messageChunkSize;
    // 是否在模型生成完成后发送标题事件，没有就发送”新对话“标题
    private final boolean sendTitleOnComplete;
    // 用于累计模型返回的正式回答
    private final StringBuilder answer = new StringBuilder();
    // 用于累计模型返回的思考过程
    private final StringBuilder thinking = new StringBuilder();
    // 模型第一次返回思考内容的时间
    private long thinkingStartMs;
    // 思考过程持续了多少秒
    private int thinkingDurationSeconds;

    /**
     * 使用参数对象构造（推荐）
     *
     * @param params 构建参数
     */
    public StreamChatEventHandler(StreamChatHandlerParams params) {
        // 原始的 SseEmitter 包装成了 SseEmitterSender
        this.sender = new SseEmitterSender(params.getEmitter());
        this.conversationId = params.getConversationId();
        this.memoryService = params.getMemoryService();
        this.conversationGroupService = params.getConversationGroupService();
        this.taskId = params.getTaskId();
        this.userId = UserContext.getUserId();
        this.taskManager = params.getTaskManager();
        this.conversationTaskTurnService = params.getConversationTaskTurnService();
        this.conversationWorkingMemoryService = params.getConversationWorkingMemoryService();

        // 计算配置
        this.messageChunkSize = resolveMessageChunkSize(params.getModelProperties());
        this.sendTitleOnComplete = shouldSendTitle();

        // 初始化（发送初始事件、注册任务）
        initialize();
    }

    /**
     * 绑定会话工作记忆任务轮次，用于流式回答完成或失败后回写处理状态。
     *
     * @param taskTurnId 会话工作记忆任务轮次ID
     *
     * 绑定会话工作记忆上下文，用于流式回答完成后更新任务压缩状态。
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param taskTurnId         会话工作记忆任务轮次ID
     * @param questionText       用户原始问题
     * @param rewriteQuestion    改写后的问题
     */
    public void bindWorkingMemory(String conversationTaskId, String taskTurnId,
                                  String questionText, String rewriteQuestion) {
        this.conversationTaskId = conversationTaskId;
        this.taskTurnId = taskTurnId;
        this.questionText = questionText;
        this.rewriteQuestion = rewriteQuestion;
    }

    /**
     * 初始化：发送元数据事件并注册任务
     */
    private void initialize() {
        // 1. 先给前端发送 meta 事件，告诉前端 conversationId 和 taskId
        // 2. 把当前 taskId 注册到 StreamTaskManager，后续可以停止生成
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        taskManager.register(taskId, sender, this::buildCompletionPayloadOnCancel);
    }

    /**
     * 解析消息块大小
     */
    private int resolveMessageChunkSize(AIModelProperties modelProperties) {
        // 如果配置了 modelProperties.stream.messageChunkSize，就用配置值,否则就是5
        return Math.max(1, Optional.ofNullable(modelProperties.getStream())
                // 从 modelProperties.stream 中获取 messageChunkSize
                .map(AIModelProperties.Stream::getMessageChunkSize)
                .orElse(5));
    }

    /**
     * 判断是否需要发送标题
     */
    private boolean shouldSendTitle() {
        // 查询数据库是否存在当前对话的conversationID和userId
        ConversationDO existingConversation = conversationGroupService.findConversation(
                conversationId,
                userId
        );
        // 如果没有对话记录，或者标题为空，就发送”新对话“标题
        return existingConversation == null || StrUtil.isBlank(existingConversation.getTitle());
    }

    /**
     * 构造取消时的完成载荷（如果有内容则先落库）
     */
    // 用户取消任务时由 StreamTaskManager 调用
    // 如果用户中途停止生成，但已经生成了一部分回答，就把这部分回答保存下来，并返回取消事件需要的数据。
    private CompletionPayload buildCompletionPayloadOnCancel() {
        String content = answer.toString();
        String messageId = null;
        if (StrUtil.isNotBlank(content)) {
            try {
                String thinkingContent = thinking.isEmpty() ? null : thinking.toString();
                ChatMessage message = ChatMessage.assistant(content, thinkingContent, resolveThinkingDuration());
                messageId = memoryService.append(conversationId, userId, message);
                markTaskTurnSuccess(messageId);
                updateConversationTaskState(content);
            } catch (Exception e) {
                log.error("取消时持久化消息失败，conversationId：{}", conversationId, e);
                markTaskTurnFailed(e.getMessage());
            }
        }
        String title = resolveTitleForEvent();
        return new CompletionPayload(String.valueOf(messageId), title);
    }

    @Override
    public void onContent(String chunk) {
        // 判断任务是否已经取消
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        // 记录思考耗时
        if (thinkingStartMs > 0 && thinkingDurationSeconds == 0) {
            thinkingDurationSeconds = Math.max(1, Math.round((System.currentTimeMillis() - thinkingStartMs) / 1000.0f));
        }
        // 把内容追加到完整答案里 stringbuilder类型
        answer.append(chunk);
        // 真正发送 SSE 的地方，拆分并发送消息块
        sendChunked(TYPE_RESPONSE, chunk);
    }

    @Override
    public void onThinking(String chunk) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        if (thinkingStartMs == 0) {
            thinkingStartMs = System.currentTimeMillis();
        }
        thinking.append(chunk);
        // // 真正发送 SSE 的地方，拆分并发送消息块
        sendChunked(TYPE_THINK, chunk);
    }

    @Override
    public void onComplete() {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        String messageId = null;
        try {
            String thinkingContent = thinking.isEmpty() ? null : thinking.toString();
            // 构建对话消息体
            ChatMessage message = ChatMessage.assistant(answer.toString(), thinkingContent, resolveThinkingDuration());
            // 追加消息
            messageId = memoryService.append(conversationId, userId, message);
            markTaskTurnSuccess(messageId);
            updateConversationTaskState(answer.toString());
        } catch (Exception e) {
            log.error("对话完成时持久化消息失败，conversationId：{}", conversationId, e);
            markTaskTurnFailed(e.getMessage());
        }
        String title = resolveTitleForEvent();
        String messageIdText = StrUtil.isBlank(messageId) ? null : messageId;
        sender.sendEvent(SSEEventType.FINISH.value(), new CompletionPayload(messageIdText, title));
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        taskManager.unregister(taskId);
        sender.complete();
    }

    @Override
    public void onError(Throwable t) {
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        markTaskTurnFailed(t == null ? null : t.getMessage());
        taskManager.unregister(taskId);
        sender.fail(t);
    }

    /**
     * 将会话工作记忆任务轮次标记为成功，并记录助手消息ID。
     *
     * @param assistantMessageId 助手消息ID
     */
    private void markTaskTurnSuccess(String assistantMessageId) {
        if (conversationTaskTurnService == null || StrUtil.isBlank(taskTurnId)) {
            return;
        }
        conversationTaskTurnService.markSuccess(taskTurnId, assistantMessageId);
    }

    /**
     * 将会话工作记忆任务轮次标记为失败，并记录错误信息。
     *
     * @param errorMessage 错误信息
     */
    private void markTaskTurnFailed(String errorMessage) {
        if (conversationTaskTurnService == null || StrUtil.isBlank(taskTurnId)) {
            return;
        }
        conversationTaskTurnService.markFailed(taskTurnId, errorMessage);
    }

    /**
     * 使用本轮问答更新会话工作记忆任务压缩状态。
     *
     * @param assistantAnswer 助手回答
     */
    private void updateConversationTaskState(String assistantAnswer) {
        if (conversationWorkingMemoryService == null
                || StrUtil.hasBlank(conversationTaskId, taskTurnId, questionText, assistantAnswer)) {
            return;
        }
        conversationWorkingMemoryService.updateConversationTaskState(
                conversationTaskId,
                conversationId,
                userId,
                questionText,
                rewriteQuestion,
                assistantAnswer
        );
    }

    private void sendChunked(String type, String content) {
        int length = content.length();
        int idx = 0;
        int count = 0;
        StringBuilder buffer = new StringBuilder();
        while (idx < length) {
            int codePoint = content.codePointAt(idx);
            buffer.appendCodePoint(codePoint);
            idx += Character.charCount(codePoint);
            count++;
            // 如果当前消息块大小超过配置值，就发送当前消息块，重置计数器和缓冲区
            if (count >= messageChunkSize) {
                // sender是SseEmitterSender，内部构造了一个sseEmitter
                sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
                buffer.setLength(0);
                count = 0;
            }
        }
        if (!buffer.isEmpty()) {
            // sender是SseEmitterSender
            sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
        }
    }

    private Integer resolveThinkingDuration() {
        return thinkingDurationSeconds > 0 ? thinkingDurationSeconds : null;
    }

    private String resolveTitleForEvent() {
        if (!sendTitleOnComplete) {
            return null;
        }
        ConversationDO conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation != null && StrUtil.isNotBlank(conversation.getTitle())) {
            return conversation.getTitle();
        }
        return "新对话";
    }
}
