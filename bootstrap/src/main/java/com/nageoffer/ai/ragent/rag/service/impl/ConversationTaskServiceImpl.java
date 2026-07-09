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

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationTaskDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationTaskMapper;
import com.nageoffer.ai.ragent.rag.service.ConversationTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 会话工作记忆任务服务实现类
 */
@Service
@RequiredArgsConstructor
public class ConversationTaskServiceImpl implements ConversationTaskService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final int INACTIVE = 0;
    private static final int ACTIVE = 1;

    private final ConversationTaskMapper conversationTaskMapper;

    /**
     * 查询指定会话当前激活的工作记忆任务
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @return 当前激活任务，不存在时返回 null
     */
    @Override
    public ConversationTaskDO getActiveTask(String conversationId, String userId) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return null;
        }
        return conversationTaskMapper.selectOne(
                Wrappers.lambdaQuery(ConversationTaskDO.class)
                        .eq(ConversationTaskDO::getConversationId, conversationId)
                        .eq(ConversationTaskDO::getUserId, userId)
                        .eq(ConversationTaskDO::getIsActive, ACTIVE)
                        .eq(ConversationTaskDO::getDeleted, 0)
        );
    }

    /**
     * 查询指定会话最近活跃的工作记忆任务
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param limit          最大返回数量
     * @return 最近活跃任务列表
     */
    @Override
    public List<ConversationTaskDO> listRecentTasks(String conversationId, String userId, int limit) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || limit <= 0) {
            return List.of();
        }
        return conversationTaskMapper.selectList(
                Wrappers.lambdaQuery(ConversationTaskDO.class)
                        .eq(ConversationTaskDO::getConversationId, conversationId)
                        .eq(ConversationTaskDO::getUserId, userId)
                        .eq(ConversationTaskDO::getDeleted, 0)
                        .orderByDesc(ConversationTaskDO::getIsActive)
                        .orderByDesc(ConversationTaskDO::getLastActiveTime)
                        .orderByDesc(ConversationTaskDO::getCreateTime)
                        .last("limit " + limit)
        );
    }

    /**
     * 查询指定工作记忆任务
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @return 工作记忆任务，不存在时返回 null
     */
    @Override
    public ConversationTaskDO getTask(String conversationTaskId, String conversationId, String userId) {
        if (StrUtil.hasBlank(conversationTaskId, conversationId, userId)) {
            return null;
        }
        return conversationTaskMapper.selectOne(
                Wrappers.lambdaQuery(ConversationTaskDO.class)
                        .eq(ConversationTaskDO::getConversationTaskId, conversationTaskId)
                        .eq(ConversationTaskDO::getConversationId, conversationId)
                        .eq(ConversationTaskDO::getUserId, userId)
                        .eq(ConversationTaskDO::getDeleted, 0)
        );
    }

    /**
     * 创建工作记忆任务，并补齐任务ID和默认状态
     *
     * @param task 工作记忆任务
     * @return 会话工作记忆任务ID
     */
    @Override
    public String createTask(ConversationTaskDO task) {
        if (task == null) {
            return null;
        }
        if (StrUtil.isBlank(task.getConversationTaskId())) {
            task.setConversationTaskId(IdUtil.getSnowflakeNextIdStr());
        }
        if (StrUtil.isBlank(task.getStatus())) {
            task.setStatus(STATUS_ACTIVE);
        }
        if (task.getIsActive() == null) {
            task.setIsActive(INACTIVE);
        }
        if (task.getTurnCount() == null) {
            task.setTurnCount(0);
        }
        if (task.getLastActiveTime() == null) {
            task.setLastActiveTime(new Date());
        }
        conversationTaskMapper.insert(task);
        return task.getConversationTaskId();
    }

    /**
     * 在事务中关闭原激活任务并激活指定任务
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @return 是否成功激活
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean activateTask(String conversationTaskId, String conversationId, String userId) {
        ConversationTaskDO targetTask = getTask(conversationTaskId, conversationId, userId);
        if (targetTask == null) {
            return false;
        }
        Date now = new Date();
        conversationTaskMapper.update(
                null,
                Wrappers.lambdaUpdate(ConversationTaskDO.class)
                        .eq(ConversationTaskDO::getConversationId, conversationId)
                        .eq(ConversationTaskDO::getUserId, userId)
                        .eq(ConversationTaskDO::getIsActive, ACTIVE)
                        .eq(ConversationTaskDO::getDeleted, 0)
                        .set(ConversationTaskDO::getIsActive, INACTIVE)
                        .set(ConversationTaskDO::getUpdateTime, now)
        );
        int updated = conversationTaskMapper.update(
                null,
                Wrappers.lambdaUpdate(ConversationTaskDO.class)
                        .eq(ConversationTaskDO::getConversationTaskId, conversationTaskId)
                        .eq(ConversationTaskDO::getConversationId, conversationId)
                        .eq(ConversationTaskDO::getUserId, userId)
                        .eq(ConversationTaskDO::getDeleted, 0)
                        .set(ConversationTaskDO::getStatus, STATUS_ACTIVE)
                        .set(ConversationTaskDO::getIsActive, ACTIVE)
                        .set(ConversationTaskDO::getLastActiveTime, now)
                        .set(ConversationTaskDO::getUpdateTime, now)
        );
        return updated > 0;
    }

    /**
     * 记录任务新建轮次后的最新轮次、消息和轮次数
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param taskTurnId         任务轮次ID
     * @param messageId          最新消息ID
     * @return 是否更新成功
     */
    @Override
    public boolean recordTurn(String conversationTaskId, String taskTurnId, String messageId) {
        if (StrUtil.isBlank(conversationTaskId) || StrUtil.isBlank(taskTurnId)) {
            return false;
        }
        Date now = new Date();
        int updated = conversationTaskMapper.update(
                null,
                Wrappers.lambdaUpdate(ConversationTaskDO.class)
                        .eq(ConversationTaskDO::getConversationTaskId, conversationTaskId)
                        .eq(ConversationTaskDO::getDeleted, 0)
                        .set(ConversationTaskDO::getLastTurnId, taskTurnId)
                        .set(StrUtil.isNotBlank(messageId), ConversationTaskDO::getLastMessageId, messageId)
                        .set(ConversationTaskDO::getLastActiveTime, now)
                        .set(ConversationTaskDO::getUpdateTime, now)
                        .setSql("turn_count = turn_count + 1")
        );
        return updated > 0;
    }

    /**
     * 记录任务最新的检索快照
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param snapshotId         检索快照ID
     * @return 是否更新成功
     */
    @Override
    public boolean recordSnapshot(String conversationTaskId, String snapshotId) {
        if (StrUtil.hasBlank(conversationTaskId, snapshotId)) {
            return false;
        }
        Date now = new Date();
        int updated = conversationTaskMapper.update(
                null,
                Wrappers.lambdaUpdate(ConversationTaskDO.class)
                        .eq(ConversationTaskDO::getConversationTaskId, conversationTaskId)
                        .eq(ConversationTaskDO::getDeleted, 0)
                        .set(ConversationTaskDO::getLastSnapshotId, snapshotId)
                        .set(ConversationTaskDO::getLastActiveTime, now)
                        .set(ConversationTaskDO::getUpdateTime, now)
        );
        return updated > 0;
    }

    /**
     * 更新工作记忆任务的压缩状态JSON。
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @param stateJson          任务压缩状态JSON
     * @return 是否更新成功
     */
    @Override
    public boolean updateStateJson(String conversationTaskId, String conversationId, String userId, String stateJson) {
        if (StrUtil.hasBlank(conversationTaskId, conversationId, userId, stateJson)) {
            return false;
        }
        Date now = new Date();
        int updated = conversationTaskMapper.update(
                null,
                Wrappers.lambdaUpdate(ConversationTaskDO.class)
                        .eq(ConversationTaskDO::getConversationTaskId, conversationTaskId)
                        .eq(ConversationTaskDO::getConversationId, conversationId)
                        .eq(ConversationTaskDO::getUserId, userId)
                        .eq(ConversationTaskDO::getDeleted, 0)
                        .set(ConversationTaskDO::getStateJson, stateJson)
                        .set(ConversationTaskDO::getLastActiveTime, now)
                        .set(ConversationTaskDO::getUpdateTime, now)
        );
        return updated > 0;
    }

    /**
     * 更新任务最后关联的消息
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param messageId          最新消息ID
     * @return 是否更新成功
     */
    @Override
    public boolean updateLastMessage(String conversationTaskId, String messageId) {
        if (StrUtil.hasBlank(conversationTaskId, messageId)) {
            return false;
        }
        Date now = new Date();
        int updated = conversationTaskMapper.update(
                null,
                Wrappers.lambdaUpdate(ConversationTaskDO.class)
                        .eq(ConversationTaskDO::getConversationTaskId, conversationTaskId)
                        .eq(ConversationTaskDO::getDeleted, 0)
                        .set(ConversationTaskDO::getLastMessageId, messageId)
                        .set(ConversationTaskDO::getLastActiveTime, now)
                        .set(ConversationTaskDO::getUpdateTime, now)
        );
        return updated > 0;
    }
}
