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

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.controller.vo.ConversationMessageVO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationMessageMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.ConversationSummaryMapper;
import com.nageoffer.ai.ragent.rag.enums.ConversationMessageOrder;
import com.nageoffer.ai.ragent.rag.service.MessageFeedbackService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationMessageBO;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationSummaryBO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话消息服务实现类
 */
@Service
@RequiredArgsConstructor
public class ConversationMessageServiceImpl implements ConversationMessageService {

    private final ConversationMessageMapper conversationMessageMapper;
    private final ConversationSummaryMapper conversationSummaryMapper;
    private final ConversationMapper conversationMapper;
    private final MessageFeedbackService feedbackService;

    /**
     * 新增一条会话消息
     *
     * @param conversationMessage 消息内容
     * @return 新增消息ID
     */
    @Override
    public String addMessage(ConversationMessageBO conversationMessage) {
        ConversationMessageDO messageDO = BeanUtil.toBean(conversationMessage, ConversationMessageDO.class);
        conversationMessageMapper.insert(messageDO);
        return messageDO.getId();
    }

    /**
     * 查询指定会话的消息列表，并附加助手消息的用户反馈
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param limit          最大返回数量
     * @param order          排序方式
     * @return 会话消息列表
     */
    @Override
    public List<ConversationMessageVO> listMessages(String conversationId, String userId, Integer limit, ConversationMessageOrder order) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId)) {
            return List.of();
        }
        // 检查当前会话是否存在
        ConversationDO conversation = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationDO.class)
                        .eq(ConversationDO::getConversationId, conversationId)
                        .eq(ConversationDO::getUserId, userId)
                        .eq(ConversationDO::getDeleted, 0)
        );
        if (conversation == null) {
            return List.of();
        }
        // 确定查询顺序，默认升序排列
        boolean asc = order == null || order == ConversationMessageOrder.ASC;
        // 查询消息表，根据对话ID和用户ID查询消息记录
        List<ConversationMessageDO> records = conversationMessageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .orderBy(true, asc, ConversationMessageDO::getCreateTime)
                        .last(limit != null, "limit " + limit)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        if (!asc) {
            Collections.reverse(records);
        }

        List<String> assistantMessageIds = records.stream()
                .filter(record -> "assistant".equalsIgnoreCase(record.getRole()))
                .map(ConversationMessageDO::getId)
                .toList();
        Map<String, Integer> votesByMessageId = feedbackService.getUserVotes(userId, assistantMessageIds);

        List<ConversationMessageVO> result = new ArrayList<>();
        for (ConversationMessageDO record : records) {
            result.add(toMessageVO(record, votesByMessageId.get(record.getId())));
        }

        return result;
    }

    /**
     * 按消息 ID 批量查询指定用户、指定会话下的消息
     *
     * <p>返回结果与 messageIds 顺序一致，不属于该用户和会话的消息不会返回。</p>
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @param messageIds     消息ID列表
     * @return 会话消息列表
     */
    @Override
    public List<ConversationMessageVO> listMessagesByIds(String conversationId, String userId, List<String> messageIds) {
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(userId) || CollUtil.isEmpty(messageIds)) {
            return List.of();
        }
        List<String> normalizedMessageIds = messageIds.stream()
                .filter(StrUtil::isNotBlank)
                .toList();
        if (normalizedMessageIds.isEmpty()) {
            return List.of();
        }

        List<ConversationMessageDO> records = conversationMessageMapper.selectList(
                Wrappers.lambdaQuery(ConversationMessageDO.class)
                        .eq(ConversationMessageDO::getConversationId, conversationId)
                        .eq(ConversationMessageDO::getUserId, userId)
                        .eq(ConversationMessageDO::getDeleted, 0)
                        .in(ConversationMessageDO::getId, normalizedMessageIds)
        );
        if (CollUtil.isEmpty(records)) {
            return List.of();
        }

        Map<String, ConversationMessageDO> recordsById = new HashMap<>();
        for (ConversationMessageDO record : records) {
            recordsById.put(record.getId(), record);
        }
        List<ConversationMessageVO> result = new ArrayList<>(normalizedMessageIds.size());
        for (String messageId : normalizedMessageIds) {
            ConversationMessageDO record = recordsById.get(messageId);
            if (record != null) {
                result.add(toMessageVO(record, null));
            }
        }
        return result;
    }

    /**
     * 新增一条会话摘要
     *
     * @param conversationSummary 会话摘要内容
     */
    @Override
    public void addMessageSummary(ConversationSummaryBO conversationSummary) {
        ConversationSummaryDO conversationSummaryDO = BeanUtil.toBean(conversationSummary, ConversationSummaryDO.class);
        conversationSummaryMapper.insert(conversationSummaryDO);
    }

    /**
     * 将消息实体转换为接口返回对象
     *
     * @param record 消息实体
     * @param vote   当前用户的反馈值
     * @return 消息返回对象
     */
    private ConversationMessageVO toMessageVO(ConversationMessageDO record, Integer vote) {
        return ConversationMessageVO.builder()
                .id(String.valueOf(record.getId()))
                .conversationId(record.getConversationId())
                .role(record.getRole())
                .content(record.getContent())
                .thinkingContent(record.getThinkingContent())
                .thinkingDuration(record.getThinkingDuration())
                .vote(vote)
                .createTime(record.getCreateTime())
                .build();
    }
}
