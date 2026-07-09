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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationRetrievalSnapshotDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationTaskDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationTaskTurnDO;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.ConversationRetrievalSnapshotService;
import com.nageoffer.ai.ragent.rag.service.ConversationTaskService;
import com.nageoffer.ai.ragent.rag.service.ConversationTaskTurnService;
import com.nageoffer.ai.ragent.rag.service.ConversationWorkingMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话工作记忆编排服务实现类。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationWorkingMemoryServiceImpl implements ConversationWorkingMemoryService {

    private static final int DEFAULT_CONTEXT_TURN_LIMIT = 3;
    private static final int CANDIDATE_TASK_LIMIT = 3;
    private static final String INHERITANCE_TYPE_NEW = "NEW";
    private static final String INHERITANCE_TYPE_REUSE_ACTIVE = "REUSE_ACTIVE";
    private static final String INHERITANCE_TYPE_SWITCH_HISTORY = "SWITCH_HISTORY";
    private static final int TOPIC_KEY_MAX_LENGTH = 128;
    private static final int GOAL_MAX_LENGTH = 512;
    private static final int STATE_JSON_INPUT_MAX_LENGTH = 3000;
    private static final int ASSISTANT_ANSWER_INPUT_MAX_LENGTH = 3000;
    private static final double LLM_MATCH_CONFIDENCE_THRESHOLD = 0.7D;

    private final ConversationTaskService conversationTaskService;
    private final ConversationTaskTurnService conversationTaskTurnService;
    private final ConversationRetrievalSnapshotService conversationRetrievalSnapshotService;
    private final ConversationMessageService conversationMessageService;
    private final LLMService llmService;

    /**
     * 优先基于任务摘要判断当前问题归属；无法匹配时创建并激活新任务。
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param questionText   用户原始问题
     * @return 会话工作记忆任务，不满足创建条件时返回 null
     */
    @Override
    public ConversationTaskDO resolveConversationTask(String conversationId, String userId, String questionText) {
        if (StrUtil.hasBlank(conversationId, userId, questionText)) {
            return null;
        }

        ConversationTaskDO matchedTask = selectMatchedTask(conversationId, userId, questionText);
        if (matchedTask != null) {
            if (matchedTask.getIsActive() == null || matchedTask.getIsActive() != 1) {
                conversationTaskService.activateTask(matchedTask.getConversationTaskId(), conversationId, userId);
            }
            return matchedTask;
        }

        ConversationTaskDO newTask = ConversationTaskDO.builder()
                .conversationId(conversationId)
                .userId(userId)
                .topicKey(truncate(questionText, TOPIC_KEY_MAX_LENGTH))
                .goal(truncate(questionText, GOAL_MAX_LENGTH))
                .build();
        String conversationTaskId = conversationTaskService.createTask(newTask);
        if (StrUtil.isBlank(conversationTaskId)) {
            return null;
        }
        conversationTaskService.activateTask(conversationTaskId, conversationId, userId);
        return conversationTaskService.getTask(conversationTaskId, conversationId, userId);
    }

    /**
     * 根据解析前的活跃任务与当前命中的任务，判断当前轮次的任务继承类型。
     *
     * @param activeTask             解析前的活跃任务
     * @param resolvedConversationTask 当前命中的任务
     * @return 任务继承类型
     */
    @Override
    public String resolveInheritanceType(ConversationTaskDO activeTask, ConversationTaskDO resolvedConversationTask) {
        if (resolvedConversationTask == null) {
            return null;
        }
        Integer turnCount = resolvedConversationTask.getTurnCount();
        if (turnCount == null || turnCount <= 0) {
            return INHERITANCE_TYPE_NEW;
        }
        if (activeTask != null
                && StrUtil.equals(activeTask.getConversationTaskId(), resolvedConversationTask.getConversationTaskId())) {
            return INHERITANCE_TYPE_REUSE_ACTIVE;
        }
        return INHERITANCE_TYPE_SWITCH_HISTORY;
    }

    /**
     * 创建任务轮次，并同步更新任务最新轮次和最新用户消息。
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @param userMessageId      用户消息ID
     * @param questionText       用户原始问题
     * @param rewriteQuestion    改写后的问题
     * @return 任务轮次ID，不满足保存条件时返回 null
     */
    @Override
    public String saveConversationTaskTurn(String conversationTaskId, String conversationId, String userId,
                                           String userMessageId, String inheritanceType,
                                           String questionText, String rewriteQuestion) {
        if (StrUtil.hasBlank(conversationTaskId, conversationId, userId, userMessageId, questionText)) {
            return null;
        }
        ConversationTaskTurnDO taskTurn = ConversationTaskTurnDO.builder()
                .conversationTaskId(conversationTaskId)
                .conversationId(conversationId)
                .userId(userId)
                .userMessageId(userMessageId)
                .inheritanceType(inheritanceType)
                .questionText(questionText)
                .rewriteQuestion(rewriteQuestion)
                .build();
        String taskTurnId = conversationTaskTurnService.createTurn(taskTurn);
        if (StrUtil.isNotBlank(taskTurnId)) {
            conversationTaskService.recordTurn(conversationTaskId, taskTurnId, userMessageId);
        }
        return taskTurnId;
    }

    /**
     * 根据任务轮次中的用户消息和助手消息ID，恢复真实会话消息上下文。
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @param limit              最大任务轮次数
     * @return 真实会话消息列表
     */
    @Override
    public List<ConversationMessageVO> loadConversationTaskContext(String conversationTaskId,
                                                                   String conversationId,
                                                                   String userId,
                                                                   int limit) {
        int actualLimit = limit > 0 ? limit : DEFAULT_CONTEXT_TURN_LIMIT;
        List<ConversationTaskTurnDO> taskTurns = conversationTaskTurnService.listRecentCompletedTurns(
                conversationTaskId, conversationId, userId, actualLimit);
        if (taskTurns.isEmpty()) {
            return List.of();
        }

        List<String> messageIds = new ArrayList<>(taskTurns.size() * 2);
        for (ConversationTaskTurnDO taskTurn : taskTurns) {
            if (StrUtil.isBlank(taskTurn.getUserMessageId()) || StrUtil.isBlank(taskTurn.getAssistantMessageId())) {
                continue;
            }
            messageIds.add(taskTurn.getUserMessageId());
            messageIds.add(taskTurn.getAssistantMessageId());
        }
        return conversationMessageService.listMessagesByIds(conversationId, userId, messageIds);
    }

    /**
     * 保存检索快照，并在保存成功后记录到所属任务上。
     *
     * @param snapshot 检索快照
     * @return 检索快照ID，不满足保存条件时返回 null
     */
    @Override
    public String saveConversationRetrievalSnapshot(ConversationRetrievalSnapshotDO snapshot) {
        if (snapshot == null || StrUtil.isBlank(snapshot.getConversationTaskId())) {
            return null;
        }
        String snapshotId = conversationRetrievalSnapshotService.saveSnapshot(snapshot);
        if (StrUtil.isNotBlank(snapshotId)) {
            conversationTaskService.recordSnapshot(snapshot.getConversationTaskId(), snapshotId);
        }
        return snapshotId;
    }

    /**
     * 根据本轮问答压缩更新任务状态JSON，用于后续任务归属和上下文恢复。
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @param questionText       用户原始问题
     * @param rewriteQuestion    改写后的问题
     * @param assistantAnswer    助手回答
     * @return 是否更新成功
     */
    @Override
    public boolean updateConversationTaskState(String conversationTaskId, String conversationId, String userId,
                                               String questionText, String rewriteQuestion, String assistantAnswer) {
        if (StrUtil.hasBlank(conversationTaskId, conversationId, userId, questionText, assistantAnswer)) {
            return false;
        }
        ConversationTaskDO task = conversationTaskService.getTask(conversationTaskId, conversationId, userId);
        if (task == null) {
            return false;
        }
        try {
            String raw = llmService.chat(buildTaskStateRequest(task, questionText, rewriteQuestion, assistantAnswer));
            String stateJson = parseTaskStateJson(raw);
            if (StrUtil.isBlank(stateJson)) {
                return false;
            }
            return conversationTaskService.updateStateJson(conversationTaskId, conversationId, userId, stateJson);
        } catch (Exception e) {
            log.warn("更新会话工作记忆任务摘要失败 - conversationTaskId={}, conversationId={}, userId={}",
                    conversationTaskId, conversationId, userId, e);
            return false;
        }
    }

    /**
     * 构建任务状态压缩的大模型请求。
     *
     * @param task            当前工作记忆任务
     * @param questionText    用户原始问题
     * @param rewriteQuestion 改写后的问题
     * @param assistantAnswer 助手回答
     * @return 大模型请求
     */
    private ChatRequest buildTaskStateRequest(ConversationTaskDO task, String questionText,
                                              String rewriteQuestion, String assistantAnswer) {
        String systemPrompt = """
                你是会话工作记忆压缩器。
                你的任务是把一个主题任务下的历史压缩状态和本轮问答合并成新的任务状态。
                要求：
                1. 只保留对后续判断任务归属、恢复上下文有帮助的信息。
                2. 不要复制长篇回答原文，要压缩成事实、进展、约束和待确认点。
                3. 最近用户问题最多保留 5 条，按时间从旧到新排列。
                4. 只返回 JSON 对象，不要输出解释性文本。
                JSON 结构固定如下：
                {
                  "summary": "任务整体摘要，不超过120字",
                  "currentGoal": "当前任务目标",
                  "progress": ["已经明确或完成的进展"],
                  "constraints": ["用户表达过的限制、偏好、边界"],
                  "openQuestions": ["后续仍需确认的问题"],
                  "recentUserQuestions": ["最近用户问题"],
                  "lastRewriteQuestion": "本轮改写后的问题",
                  "lastUpdatedReason": "本轮更新原因，不超过60字"
                }
                """;
        String userPrompt = """
                当前任务基础信息：
                conversationTaskId=%s
                topicKey=%s
                goal=%s

                历史压缩状态：
                %s

                本轮用户原始问题：
                %s

                本轮改写问题：
                %s

                本轮助手回答：
                %s
                """.formatted(
                task.getConversationTaskId(),
                StrUtil.blankToDefault(task.getTopicKey(), ""),
                StrUtil.blankToDefault(task.getGoal(), ""),
                StrUtil.blankToDefault(truncate(task.getStateJson(), STATE_JSON_INPUT_MAX_LENGTH), "{}"),
                questionText,
                StrUtil.blankToDefault(rewriteQuestion, ""),
                truncate(assistantAnswer, ASSISTANT_ANSWER_INPUT_MAX_LENGTH)
        );
        return ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(systemPrompt),
                        ChatMessage.user(userPrompt)
                ))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
    }

    /**
     * 解析大模型返回的任务压缩状态JSON。
     *
     * @param raw 大模型原始响应
     * @return 可写入 state_json 的 JSON 字符串，解析失败时返回 null
     */
    private String parseTaskStateJson(String raw) {
        try {
            String cleanedRaw = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            JsonElement root = JsonParser.parseString(cleanedRaw);
            if (!root.isJsonObject()) {
                return null;
            }
            return root.getAsJsonObject().toString();
        } catch (Exception e) {
            log.warn("解析会话工作记忆任务摘要 LLM 响应失败，raw={}", raw, e);
            return null;
        }
    }

    /**
     * 从当前会话最近任务中选择与用户问题最相关的任务。
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param questionText   用户原始问题
     * @return 匹配到的任务，未达到阈值时返回 null
     */
    private ConversationTaskDO selectMatchedTask(String conversationId, String userId, String questionText) {
        List<TaskCandidate> candidates = loadTaskCandidates(conversationId, userId);
        if (candidates.isEmpty()) {
            return null;
        }

        try {
            String raw = llmService.chat(buildTaskMatchRequest(questionText, candidates));
            TaskMatchDecision decision = parseTaskMatchDecision(raw);
            if (decision == null || !"SELECT".equalsIgnoreCase(decision.action())) {
                return null;
            }
            if (decision.confidence() < LLM_MATCH_CONFIDENCE_THRESHOLD) {
                return null;
            }
            return findCandidateTask(candidates, decision.conversationTaskId());
        } catch (Exception e) {
            log.warn("会话工作记忆任务归属 LLM 判断失败，降级创建新任务 - conversationId={}, userId={}",
                    conversationId, userId, e);
            return null;
        }
    }

    /**
     * 加载当前会话最近任务摘要，供大模型判断当前问题归属。
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @return 候选任务列表
     */
    private List<TaskCandidate> loadTaskCandidates(String conversationId, String userId) {
        List<ConversationTaskDO> recentTasks = conversationTaskService.listRecentTasks(
                conversationId, userId, CANDIDATE_TASK_LIMIT);
        if (recentTasks.isEmpty()) {
            return List.of();
        }

        return recentTasks.stream()
                .map(TaskCandidate::new)
                .toList();
    }

    /**
     * 构造任务归属判断的大模型请求。
     *
     * @param questionText 用户原始问题
     * @param candidates   候选任务列表
     * @return 大模型请求
     */
    private ChatRequest buildTaskMatchRequest(String questionText, List<TaskCandidate> candidates) {
        String systemPrompt = """
                你是会话工作记忆任务归属判断器。
                你的任务是判断“当前用户问题”是否属于候选任务中的某一个。
                判断时优先参考候选任务的 stateJson 摘要，其次再参考 topicKey 和 goal。
                不要因为某个任务是 active 就默认选择它。
                如果无法明确判断，或者置信度不足，请返回 CREATE_NEW。

                只允许返回 JSON 对象，不要输出解释性文本：
                {
                  "action": "SELECT 或 CREATE_NEW",
                  "conversationTaskId": "当 action=SELECT 时填写候选任务ID，否则为空字符串",
                  "confidence": 0.0,
                  "reason": "简短原因"
                }
                """;
        return ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(systemPrompt),
                        ChatMessage.user(buildTaskMatchUserPrompt(questionText, candidates))
                ))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
    }

    /**
     * 构造任务归属判断的用户提示词。
     *
     * @param questionText 用户原始问题
     * @param candidates   候选任务列表
     * @return 用户提示词
     */
    private String buildTaskMatchUserPrompt(String questionText, List<TaskCandidate> candidates) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前用户问题：\n").append(questionText).append("\n\n");
        prompt.append("候选任务：\n");
        for (int i = 0; i < candidates.size(); i++) {
            TaskCandidate candidate = candidates.get(i);
            ConversationTaskDO task = candidate.task();
            prompt.append(i + 1).append(". conversationTaskId=").append(task.getConversationTaskId()).append("\n");
            prompt.append("   active=").append(task.getIsActive()).append("\n");
            appendPromptField(prompt, "topicKey", task.getTopicKey());
            appendPromptField(prompt, "goal", task.getGoal());
            appendPromptField(prompt, "stateJson", task.getStateJson());
            prompt.append("\n");
        }
        return prompt.toString();
    }

    /**
     * 追加提示词字段。
     *
     * @param prompt 提示词
     * @param name   字段名
     * @param value  字段值
     */
    private void appendPromptField(StringBuilder prompt, String name, String value) {
        prompt.append("   ").append(name).append("=").append(StrUtil.blankToDefault(value, "无")).append("\n");
    }

    /**
     * 解析大模型返回的任务归属判断结果。
     *
     * @param raw 大模型原始响应
     * @return 任务归属判断结果，解析失败时返回 null
     */
    private TaskMatchDecision parseTaskMatchDecision(String raw) {
        try {
            String cleanedRaw = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            JsonElement root = JsonParser.parseString(cleanedRaw);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            String action = getString(obj, "action");
            String conversationTaskId = getString(obj, "conversationTaskId");
            double confidence = obj.has("confidence") ? obj.get("confidence").getAsDouble() : 0D;
            String reason = getString(obj, "reason");
            return new TaskMatchDecision(action, conversationTaskId, confidence, reason);
        } catch (Exception e) {
            log.warn("解析会话工作记忆任务归属 LLM 响应失败，raw={}", raw, e);
            return null;
        }
    }

    /**
     * 从 JSON 对象中读取字符串字段。
     *
     * @param obj  JSON 对象
     * @param name 字段名
     * @return 字符串字段值
     */
    private String getString(JsonObject obj, String name) {
        if (!obj.has(name) || obj.get(name).isJsonNull()) {
            return "";
        }
        return obj.get(name).getAsString();
    }

    /**
     * 根据会话工作记忆任务ID查找候选任务。
     *
     * @param candidates         候选任务列表
     * @param conversationTaskId 会话工作记忆任务ID
     * @return 匹配的候选任务，不存在时返回 null
     */
    private ConversationTaskDO findCandidateTask(List<TaskCandidate> candidates, String conversationTaskId) {
        if (StrUtil.isBlank(conversationTaskId)) {
            return null;
        }
        return candidates.stream()
                .map(TaskCandidate::task)
                .filter(task -> conversationTaskId.equals(task.getConversationTaskId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按数据库字段长度截断文本，避免基础编排阶段写入过长主题或目标。
     *
     * @param text      原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    /**
     * 任务候选上下文。
     *
     * @param task 候选任务
     */
    private record TaskCandidate(ConversationTaskDO task) {
    }

    /**
     * 大模型任务归属判断结果。
     *
     * @param action             选择动作
     * @param conversationTaskId 会话工作记忆任务ID
     * @param confidence         置信度
     * @param reason             选择原因
     */
    private record TaskMatchDecision(String action, String conversationTaskId, double confidence, String reason) {
    }
}
