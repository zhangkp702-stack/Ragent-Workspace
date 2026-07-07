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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话工作记忆编排服务实现类。
 */
@Service
@RequiredArgsConstructor
public class ConversationWorkingMemoryServiceImpl implements ConversationWorkingMemoryService {

    private static final int DEFAULT_CONTEXT_TURN_LIMIT = 6;
    private static final int CANDIDATE_TASK_LIMIT = 5;
    private static final int CANDIDATE_RECENT_QUESTION_LIMIT = 5;
    private static final int TOPIC_KEY_MAX_LENGTH = 128;
    private static final int GOAL_MAX_LENGTH = 512;
    private static final double TASK_MATCH_THRESHOLD = 0.25D;
    private static final double ACTIVE_TASK_SCORE_BONUS = 0.03D;
    private static final Set<String> STOP_TOKENS = Set.of(
            "什么", "怎么", "如何", "哪些", "需要", "可以", "这个", "那个", "刚才", "一下", "还有", "是否"
    );

    private final ConversationTaskService conversationTaskService;
    private final ConversationTaskTurnService conversationTaskTurnService;
    private final ConversationRetrievalSnapshotService conversationRetrievalSnapshotService;
    private final ConversationMessageService conversationMessageService;

    /**
     * 优先复用当前活跃任务；没有活跃任务时创建并激活新任务。
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
                                           String userMessageId, String questionText, String rewriteQuestion) {
        if (StrUtil.hasBlank(conversationTaskId, conversationId, userId, userMessageId, questionText)) {
            return null;
        }
        ConversationTaskTurnDO taskTurn = ConversationTaskTurnDO.builder()
                .conversationTaskId(conversationTaskId)
                .conversationId(conversationId)
                .userId(userId)
                .userMessageId(userMessageId)
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
        List<ConversationTaskTurnDO> taskTurns = conversationTaskTurnService.listRecentTurns(
                conversationTaskId, conversationId, userId, actualLimit);
        if (taskTurns.isEmpty()) {
            return List.of();
        }

        List<String> messageIds = new ArrayList<>(taskTurns.size() * 2);
        for (ConversationTaskTurnDO taskTurn : taskTurns) {
            if (StrUtil.isNotBlank(taskTurn.getUserMessageId())) {
                messageIds.add(taskTurn.getUserMessageId());
            }
            if (StrUtil.isNotBlank(taskTurn.getAssistantMessageId())) {
                messageIds.add(taskTurn.getAssistantMessageId());
            }
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
     * 从当前会话最近任务中选择与用户问题最相关的任务。
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param questionText   用户原始问题
     * @return 匹配到的任务，未达到阈值时返回 null
     */
    private ConversationTaskDO selectMatchedTask(String conversationId, String userId, String questionText) {
        List<ConversationTaskDO> recentTasks = conversationTaskService.listRecentTasks(
                conversationId, userId, CANDIDATE_TASK_LIMIT);
        if (recentTasks.isEmpty()) {
            return null;
        }

        ConversationTaskDO matchedTask = null;
        double matchedScore = 0D;
        for (ConversationTaskDO recentTask : recentTasks) {
            double score = calculateTaskScore(recentTask, conversationId, userId, questionText);
            if (score > matchedScore) {
                matchedTask = recentTask;
                matchedScore = score;
            }
        }
        if (matchedScore < TASK_MATCH_THRESHOLD) {
            return null;
        }
        return matchedTask;
    }

    /**
     * 根据任务基础信息和最近用户问题计算任务匹配分。
     *
     * @param task           候选任务
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param questionText   用户原始问题
     * @return 匹配分
     */
    private double calculateTaskScore(ConversationTaskDO task, String conversationId, String userId,
                                      String questionText) {
        double score = 0D;
        score = Math.max(score, calculateTextSimilarity(questionText, task.getTopicKey()) * 0.8D);
        score = Math.max(score, calculateTextSimilarity(questionText, task.getGoal()) * 0.9D);
        score = Math.max(score, calculateTextSimilarity(questionText, task.getStateJson()) * 0.7D);

        List<ConversationTaskTurnDO> recentTurns = conversationTaskTurnService.listRecentTurns(
                task.getConversationTaskId(), conversationId, userId, CANDIDATE_RECENT_QUESTION_LIMIT);
        for (ConversationTaskTurnDO recentTurn : recentTurns) {
            score = Math.max(score, calculateTextSimilarity(questionText, recentTurn.getQuestionText()));
        }

        if (task.getIsActive() != null && task.getIsActive() == 1 && score > 0D) {
            score += ACTIVE_TASK_SCORE_BONUS;
        }
        return score;
    }

    /**
     * 计算用户问题中的关键词在候选文本中的命中比例。
     *
     * @param questionText 用户原始问题
     * @param targetText   候选文本
     * @return 相似度分数
     */
    private double calculateTextSimilarity(String questionText, String targetText) {
        Set<String> questionTokens = extractTokens(questionText);
        Set<String> targetTokens = extractTokens(targetText);
        if (questionTokens.isEmpty() || targetTokens.isEmpty()) {
            return 0D;
        }

        Set<String> matchedTokens = new HashSet<>(questionTokens);
        matchedTokens.retainAll(targetTokens);
        return (double) matchedTokens.size() / questionTokens.size();
    }

    /**
     * 从文本中提取用于轻量匹配的关键词。
     *
     * @param text 原始文本
     * @return 关键词集合
     */
    private Set<String> extractTokens(String text) {
        if (StrUtil.isBlank(text)) {
            return Set.of();
        }
        String normalizedText = text.toLowerCase();
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder word = new StringBuilder();
        StringBuilder chineseText = new StringBuilder();

        for (int i = 0; i < normalizedText.length(); i++) {
            char currentChar = normalizedText.charAt(i);
            if (isChinese(currentChar)) {
                flushWordToken(word, tokens);
                chineseText.append(currentChar);
            } else {
                flushChineseTokens(chineseText, tokens);
                if (Character.isLetterOrDigit(currentChar)) {
                    word.append(currentChar);
                } else {
                    flushWordToken(word, tokens);
                }
            }
        }
        flushWordToken(word, tokens);
        flushChineseTokens(chineseText, tokens);
        tokens.removeAll(STOP_TOKENS);
        return tokens;
    }

    /**
     * 判断字符是否为中文字符。
     *
     * @param currentChar 当前字符
     * @return 是否为中文字符
     */
    private boolean isChinese(char currentChar) {
        return Character.UnicodeScript.of(currentChar) == Character.UnicodeScript.HAN;
    }

    /**
     * 写入英文或数字关键词。
     *
     * @param word   当前词
     * @param tokens 关键词集合
     */
    private void flushWordToken(StringBuilder word, Set<String> tokens) {
        if (word.length() > 1) {
            tokens.add(word.toString());
        }
        word.setLength(0);
    }

    /**
     * 将连续中文文本切分成双字关键词。
     *
     * @param chineseText 连续中文文本
     * @param tokens      关键词集合
     */
    private void flushChineseTokens(StringBuilder chineseText, Set<String> tokens) {
        if (chineseText.length() == 1) {
            tokens.add(chineseText.toString());
        }
        for (int i = 0; i < chineseText.length() - 1; i++) {
            tokens.add(chineseText.substring(i, i + 2));
        }
        chineseText.setLength(0);
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
}
