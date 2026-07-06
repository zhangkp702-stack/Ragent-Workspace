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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationTaskTurnDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationTaskTurnMapper;
import com.nageoffer.ai.ragent.rag.service.ConversationTaskTurnService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 会话工作记忆任务轮次服务实现类
 */
@Service
@RequiredArgsConstructor
public class ConversationTaskTurnServiceImpl implements ConversationTaskTurnService {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    private final ConversationTaskTurnMapper conversationTaskTurnMapper;

    /**
     * 创建工作记忆任务轮次，并补齐默认处理状态
     *
     * @param taskTurn 工作记忆任务轮次
     * @return 任务轮次ID
     */
    @Override
    public String createTurn(ConversationTaskTurnDO taskTurn) {
        if (taskTurn == null) {
            return null;
        }
        if (StrUtil.isBlank(taskTurn.getStatus())) {
            taskTurn.setStatus(STATUS_RUNNING);
        }
        conversationTaskTurnMapper.insert(taskTurn);
        return taskTurn.getId();
    }

    /**
     * 查询指定任务最近的轮次，并将数据库倒序结果恢复为时间升序
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @param limit              最大返回数量
     * @return 最近任务轮次列表
     */
    @Override
    public List<ConversationTaskTurnDO> listRecentTurns(String conversationTaskId, String conversationId,
                                                        String userId, int limit) {
        if (StrUtil.hasBlank(conversationTaskId, conversationId, userId) || limit <= 0) {
            return List.of();
        }
        List<ConversationTaskTurnDO> records = conversationTaskTurnMapper.selectList(
                Wrappers.lambdaQuery(ConversationTaskTurnDO.class)
                        .eq(ConversationTaskTurnDO::getConversationTaskId, conversationTaskId)
                        .eq(ConversationTaskTurnDO::getConversationId, conversationId)
                        .eq(ConversationTaskTurnDO::getUserId, userId)
                        .eq(ConversationTaskTurnDO::getDeleted, 0)
                        .orderByDesc(ConversationTaskTurnDO::getCreateTime)
                        .last("limit " + limit)
        );
        Collections.reverse(records);
        return records;
    }

    /**
     * 更新任务轮次的改写问题
     *
     * @param taskTurnId      任务轮次ID
     * @param rewriteQuestion 改写后的问题
     * @return 是否更新成功
     */
    @Override
    public boolean updateRewriteResult(String taskTurnId, String rewriteQuestion) {
        if (StrUtil.isBlank(taskTurnId)) {
            return false;
        }
        int updated = conversationTaskTurnMapper.update(
                null,
                Wrappers.lambdaUpdate(ConversationTaskTurnDO.class)
                        .eq(ConversationTaskTurnDO::getId, taskTurnId)
                        .eq(ConversationTaskTurnDO::getDeleted, 0)
                        .set(ConversationTaskTurnDO::getRewriteQuestion, rewriteQuestion)
                        .set(ConversationTaskTurnDO::getUpdateTime, new Date())
        );
        return updated > 0;
    }

    /**
     * 更新任务轮次的检索模式
     *
     * @param taskTurnId    任务轮次ID
     * @param retrievalMode 检索模式
     * @return 是否更新成功
     */
    @Override
    public boolean updateRetrievalMode(String taskTurnId, String retrievalMode) {
        if (StrUtil.isBlank(taskTurnId)) {
            return false;
        }
        int updated = conversationTaskTurnMapper.update(
                null,
                Wrappers.lambdaUpdate(ConversationTaskTurnDO.class)
                        .eq(ConversationTaskTurnDO::getId, taskTurnId)
                        .eq(ConversationTaskTurnDO::getDeleted, 0)
                        .set(ConversationTaskTurnDO::getRetrievalMode, retrievalMode)
                        .set(ConversationTaskTurnDO::getUpdateTime, new Date())
        );
        return updated > 0;
    }

    /**
     * 将任务轮次标记为成功并记录助手消息
     *
     * @param taskTurnId         任务轮次ID
     * @param assistantMessageId 助手消息ID
     * @return 是否更新成功
     */
    @Override
    public boolean markSuccess(String taskTurnId, String assistantMessageId) {
        if (StrUtil.isBlank(taskTurnId)) {
            return false;
        }
        int updated = conversationTaskTurnMapper.update(
                null,
                Wrappers.lambdaUpdate(ConversationTaskTurnDO.class)
                        .eq(ConversationTaskTurnDO::getId, taskTurnId)
                        .eq(ConversationTaskTurnDO::getDeleted, 0)
                        .set(ConversationTaskTurnDO::getStatus, STATUS_SUCCESS)
                        .set(StrUtil.isNotBlank(assistantMessageId),
                                ConversationTaskTurnDO::getAssistantMessageId, assistantMessageId)
                        .set(ConversationTaskTurnDO::getErrorMessage, null)
                        .set(ConversationTaskTurnDO::getUpdateTime, new Date())
        );
        return updated > 0;
    }

    /**
     * 将任务轮次标记为失败并记录失败原因
     *
     * @param taskTurnId  任务轮次ID
     * @param errorMessage 失败原因
     * @return 是否更新成功
     */
    @Override
    public boolean markFailed(String taskTurnId, String errorMessage) {
        if (StrUtil.isBlank(taskTurnId)) {
            return false;
        }
        int updated = conversationTaskTurnMapper.update(
                null,
                Wrappers.lambdaUpdate(ConversationTaskTurnDO.class)
                        .eq(ConversationTaskTurnDO::getId, taskTurnId)
                        .eq(ConversationTaskTurnDO::getDeleted, 0)
                        .set(ConversationTaskTurnDO::getStatus, STATUS_FAILED)
                        .set(ConversationTaskTurnDO::getErrorMessage, errorMessage)
                        .set(ConversationTaskTurnDO::getUpdateTime, new Date())
        );
        return updated > 0;
    }
}
