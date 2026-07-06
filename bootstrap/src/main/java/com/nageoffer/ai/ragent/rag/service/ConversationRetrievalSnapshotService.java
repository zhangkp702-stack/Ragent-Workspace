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

import com.nageoffer.ai.ragent.rag.dao.entity.ConversationRetrievalSnapshotDO;

import java.util.List;

/**
 * 会话检索快照服务，对应 t_conversation_retrieval_snapshot 表
 */
public interface ConversationRetrievalSnapshotService {

    /**
     * 保存会话检索快照
     *
     * @param snapshot 检索快照
     * @return 检索快照ID
     */
    String saveSnapshot(ConversationRetrievalSnapshotDO snapshot);

    /**
     * 查询指定任务最近的检索快照，按创建时间倒序返回
     *
     * @param conversationTaskId 会话工作记忆任务ID
     * @param conversationId     会话ID
     * @param userId             用户ID
     * @param limit              最大返回数量
     * @return 最近检索快照列表
     */
    List<ConversationRetrievalSnapshotDO> listRecentSnapshots(String conversationTaskId, String conversationId,
                                                              String userId, int limit);
}
