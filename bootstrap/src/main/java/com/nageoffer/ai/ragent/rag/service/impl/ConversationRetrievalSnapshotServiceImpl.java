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
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationRetrievalSnapshotDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationRetrievalSnapshotMapper;
import com.nageoffer.ai.ragent.rag.service.ConversationRetrievalSnapshotService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话检索快照服务实现类
 */
@Service
@RequiredArgsConstructor
public class ConversationRetrievalSnapshotServiceImpl implements ConversationRetrievalSnapshotService {

    private final ConversationRetrievalSnapshotMapper conversationRetrievalSnapshotMapper;

    /**
     * 保存会话检索快照
     *
     * @param snapshot 检索快照
     * @return 检索快照ID
     */
    @Override
    public String saveSnapshot(ConversationRetrievalSnapshotDO snapshot) {
        if (snapshot == null) {
            return null;
        }
        conversationRetrievalSnapshotMapper.insert(snapshot);
        return snapshot.getId();
    }

    /**
     * 查询指定任务最近的检索快照，按创建时间倒序返回
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @param limit              最大返回数量
     * @return 最近检索快照列表
     */
    @Override
    public List<ConversationRetrievalSnapshotDO> listRecentSnapshots(String conversationTaskId,
                                                                     String conversationId,
                                                                     String userId,
                                                                     int limit) {
        if (StrUtil.hasBlank(conversationTaskId, conversationId, userId) || limit <= 0) {
            return List.of();
        }
        return conversationRetrievalSnapshotMapper.selectList(
                Wrappers.lambdaQuery(ConversationRetrievalSnapshotDO.class)
                        .eq(ConversationRetrievalSnapshotDO::getConversationTaskId, conversationTaskId)
                        .eq(ConversationRetrievalSnapshotDO::getConversationId, conversationId)
                        .eq(ConversationRetrievalSnapshotDO::getUserId, userId)
                        .eq(ConversationRetrievalSnapshotDO::getDeleted, 0)
                        .orderByDesc(ConversationRetrievalSnapshotDO::getCreateTime)
                        .last("limit " + limit)
        );
    }
}
