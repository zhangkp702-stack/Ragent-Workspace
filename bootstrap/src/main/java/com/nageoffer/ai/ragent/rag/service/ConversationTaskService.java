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

import com.nageoffer.ai.ragent.rag.dao.entity.ConversationTaskDO;

import java.util.List;

/**
 * 会话工作记忆任务服务，对应 t_conversation_task 表
 */
public interface ConversationTaskService {

    /**
     * 查询指定会话当前激活的工作记忆任务
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @return 当前激活任务，不存在时返回 null
     */
    ConversationTaskDO getActiveTask(String conversationId, String userId);

    /**
     * 查询指定会话最近活跃的工作记忆任务
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param limit          最大返回数量
     * @return 最近活跃任务列表
     */
    List<ConversationTaskDO> listRecentTasks(String conversationId, String userId, int limit);

    /**
     * 查询指定工作记忆任务
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @return 工作记忆任务，不存在时返回 null
     */
    ConversationTaskDO getTask(String conversationTaskId, String conversationId, String userId);

    /**
     * 创建工作记忆任务
     *
     * @param task 工作记忆任务
     * @return 会话工作记忆任务ID
     */
    String createTask(ConversationTaskDO task);

    /**
     * 将指定工作记忆任务设置为当前会话的激活任务
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @return 是否成功激活
     */
    boolean activateTask(String conversationTaskId, String conversationId, String userId);

    /**
     * 记录任务新建轮次后的最新轮次、消息和轮次数
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param taskTurnId         任务轮次ID
     * @param messageId          最新消息ID
     * @return 是否更新成功
     */
    boolean recordTurn(String conversationTaskId, String taskTurnId, String messageId);

    /**
     * 记录任务最新的检索快照
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param snapshotId         检索快照ID
     * @return 是否更新成功
     */
    boolean recordSnapshot(String conversationTaskId, String snapshotId);

    /**
     * 更新任务最后关联的消息
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param messageId          最新消息ID
     * @return 是否更新成功
     */
    boolean updateLastMessage(String conversationTaskId, String messageId);
}
