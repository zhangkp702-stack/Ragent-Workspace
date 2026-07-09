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

import com.nageoffer.ai.ragent.rag.dao.entity.ConversationTaskTurnDO;

import java.util.List;

/**
 * 会话工作记忆任务轮次服务，对应 t_conversation_task_turn 表
 */
public interface ConversationTaskTurnService {

    /**
     * 创建工作记忆任务轮次
     *
     * @param taskTurn 工作记忆任务轮次
     * @return 任务轮次ID
     */
    String createTurn(ConversationTaskTurnDO taskTurn);

    /**
     * 查询指定任务最近的轮次，并按创建时间升序返回
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @param limit              最大返回数量
     * @return 最近任务轮次列表
     */
    List<ConversationTaskTurnDO> listRecentTurns(String conversationTaskId, String conversationId,
                                                 String userId, int limit);

    /**
     * 查询指定任务最近已完成的轮次，并按创建时间升序返回。
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @param limit              最大返回数量
     * @return 最近已完成任务轮次列表
     */
    List<ConversationTaskTurnDO> listRecentCompletedTurns(String conversationTaskId, String conversationId,
                                                          String userId, int limit);

    /**
     * 更新任务轮次的改写问题
     *
     * @param taskTurnId      任务轮次ID
     * @param rewriteQuestion 改写后的问题
     * @return 是否更新成功
     */
    boolean updateRewriteResult(String taskTurnId, String rewriteQuestion);

    /**
     * 更新任务轮次的检索模式
     *
     * @param taskTurnId    任务轮次ID
     * @param retrievalMode 检索模式
     * @return 是否更新成功
     */
    boolean updateRetrievalMode(String taskTurnId, String retrievalMode);

    /**
     * 将任务轮次标记为成功并记录助手消息
     *
     * @param taskTurnId         任务轮次ID
     * @param assistantMessageId 助手消息ID
     * @return 是否更新成功
     */
    boolean markSuccess(String taskTurnId, String assistantMessageId);

    /**
     * 将任务轮次标记为失败并记录失败原因
     *
     * @param taskTurnId  任务轮次ID
     * @param errorMessage 失败原因
     * @return 是否更新成功
     */
    boolean markFailed(String taskTurnId, String errorMessage);
}
