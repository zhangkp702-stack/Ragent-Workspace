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

package com.nageoffer.ai.ragent.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationTaskDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 会话工作记忆任务数据访问层
 *
 * <p>基础增删改查方法由 {@link BaseMapper} 提供；后续新增自定义查询时，需要为每个方法添加方法级注释。</p>
 */
public interface ConversationTaskMapper extends BaseMapper<ConversationTaskDO> {

    @Update("""
            UPDATE t_conversation_task
            SET state_json = CAST(#{stateJson} AS jsonb),
                last_active_time = #{now},
                update_time = #{now}
            WHERE conversation_task_id = #{conversationTaskId}
              AND conversation_id = #{conversationId}
              AND user_id = #{userId}
              AND deleted = 0
            """)
    int updateStateJson(@Param("conversationTaskId") String conversationTaskId,
                        @Param("conversationId") String conversationId,
                        @Param("userId") String userId,
                        @Param("stateJson") String stateJson,
                        @Param("now") Date now);
}
