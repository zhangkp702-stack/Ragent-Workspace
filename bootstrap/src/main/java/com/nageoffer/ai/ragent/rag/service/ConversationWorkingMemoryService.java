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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationRetrievalSnapshotDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationTaskDO;

import java.util.List;

/**
 * 会话工作记忆编排服务，负责串联任务、任务轮次和检索快照服务。
 */
public interface ConversationWorkingMemoryService {

    /**
     * 解析当前用户问题所属的会话工作记忆任务。
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param questionText   用户原始问题
     * @return 会话工作记忆任务，不满足创建条件时返回 null
     */
    ConversationTaskDO resolveConversationTask(String conversationId, String userId, String questionText);

    /**
     * 根据解析前的活跃任务和当前命中的任务，判断当前轮次的任务继承类型。
     *
     * @param activeTask              解析前的活跃任务
     * @param resolvedConversationTask 当前命中的任务
     * @return 任务继承类型
     */
    String resolveInheritanceType(ConversationTaskDO activeTask, ConversationTaskDO resolvedConversationTask);

    /**
     * 保存当前用户问题对应的工作记忆任务轮次。
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @param userMessageId      用户消息ID
     * @param questionText       用户原始问题
     * @param rewriteQuestion    改写后的问题
     * @return 任务轮次ID，不满足保存条件时返回 null
     */
    String saveConversationTaskTurn(String conversationTaskId, String conversationId, String userId,
                                    String userMessageId, String inheritanceType,
                                    String questionText, String rewriteQuestion);

    /**
     * 加载指定工作记忆任务最近几轮关联的真实会话消息。
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @param limit              最大任务轮次数
     * @return 真实会话消息列表
     */
    List<ConversationMessageVO> loadConversationTaskContext(String conversationTaskId, String conversationId,
                                                            String userId, int limit);

    /**
     * 保存当前轮次的检索快照，并回写任务最新快照ID。
     *
     * @param snapshot 检索快照
     * @return 检索快照ID，不满足保存条件时返回 null
     */
    String saveConversationRetrievalSnapshot(ConversationRetrievalSnapshotDO snapshot);

    /**
     * 根据本轮问答更新会话工作记忆任务的压缩状态。
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @param questionText       用户原始问题
     * @param rewriteQuestion    改写后的问题
     * @param assistantAnswer    助手回答
     * @return 是否更新成功
     */
    boolean updateConversationTaskState(String conversationTaskId, String conversationId, String userId,
                                        String questionText, String rewriteQuestion, String assistantAnswer);
}
