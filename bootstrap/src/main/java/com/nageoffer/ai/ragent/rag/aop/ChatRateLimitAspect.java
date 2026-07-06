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

package com.nageoffer.ai.ragent.rag.aop;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;

/**
 * SSE 全局限流切面，避免业务代码侵入
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ChatRateLimitAspect {

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_ERROR = "ERROR";

    private final ChatQueueLimiter chatQueueLimiter;
    private final RagTraceProperties ragTraceProperties;
    private final RagTraceRecordService traceRecordService;

    @Around("@annotation(com.nageoffer.ai.ragent.rag.aop.ChatRateLimit)")
    public Object limitStreamChat(ProceedingJoinPoint joinPoint) throws Throwable {
        // 取出原方法参数
        Object[] args = joinPoint.getArgs();
        // 判断是不是预期的 SSE 流式聊天方法
        // 1. 参数不能为空
        // 2. 参数数量必须大于等于 4
        // 3. 第四个参数必须是 SseEmitter 类型
        if (args == null || args.length < 4 || !(args[3] instanceof SseEmitter emitter)) {
            // 如果被拦截的方法参数不符合预期，就不要强行套用聊天限流逻辑，直接执行原方法。
            return joinPoint.proceed();
        }
        // 如果第一个参数是 String，就认为它是用户问题 question；
        // 如果第二个参数是 String，就认为它是会话ID conversationId；
        String question = args[0] instanceof String q ? q : "";
        String conversationId = args[1] instanceof String cid ? cid : null;
        // 如果 conversationId 为空，则生成一个新的会话ID
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        args[1] = actualConversationId;
        // target：被代理的目标对象，也就是 RAGChatServiceImpl 对象
        // method：被拦截的真实方法，也就是 streamChat 方法
        // args：这个方法的参数
        Object target = joinPoint.getTarget();
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        // 调用聊天限流器，将用户问题 question、会话ID、SseEmitter 作为参数，加入到队列中
        chatQueueLimiter.enqueue(question, actualConversationId, emitter, () -> {
            // 把真正要执行的逻辑封装成一个 Runnable
            invokeWithTrace(method, target, args, question, actualConversationId, emitter);
        });
        return null;
    }
    // 调用原来的 streamChat(...)，并在调用前后记录 RAG trace。
    private void invokeWithTrace(Method method,
                                 Object target,
                                 Object[] args,
                                 String question,
                                 String conversationId,
                                 SseEmitter emitter) {
        if (!ragTraceProperties.isEnabled()) {
            // 没有开启 RAG trace 采集，直接执行原方法
            invokeTarget(method, target, args, emitter);
            return;
        }
        //  一次 RAG 请求的链路追踪 ID。
        String traceId = IdUtil.getSnowflakeNextIdStr();
        //  一次流式生成任务 ID，用于停止生成、任务管理、前端识别。
        String taskId = IdUtil.getSnowflakeNextIdStr();
        // 用来计算整个运行耗时。
        long startMillis = System.currentTimeMillis();
        // 记录 RAG 请求的链路追踪信息 表示：一次 RAG 流式聊天请求开始运行了。
        traceRecordService.startRun(RagTraceRunDO.builder()
                // 本次链路 ID。
                .traceId(traceId)
                // 固定写成 rag-stream-chat，表示这是一次 RAG 流式聊天。
                .traceName("rag-stream-chat")
                // 入口方法 例如 xxx.RAGChatServiceImpl#streamChat。
                .entryMethod(method.getDeclaringClass().getName() + "#" + method.getName())
                // conversationId：
                .conversationId(conversationId)
                // 当前流式任务 ID。
                .taskId(taskId)
                // 用户id
                .userId(UserContext.getUserId())
                // 初始状态为 RUNNING，表示请求正在处理中。
                .status(STATUS_RUNNING)
                .startTime(new Date())
                //  附加信息，这里记录了 questionLength，即用户问题长度。
                .extraData(StrUtil.format("{\"questionLength\":{}}", StrUtil.length(question)))
                .build());
        // 设置线程上下文
        RagTraceContext.setTraceId(traceId);
        RagTraceContext.setTaskId(taskId);
        try {
            // 反射调用真正的 streamChat(...)
            method.invoke(target, args);
            // 它记录的是入口方法执行是否成功。
            traceRecordService.finishRun(
                    traceId,
                    STATUS_SUCCESS,
                    null,
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
        } catch (Throwable ex) {
            // 反射调用时，如果原方法内部抛异常，外面拿到的通常不是原始异常，而是：
            // InvocationTargetException
            // 所以需要 unwrap(...) 拿到真实原因。
            Throwable cause = unwrap(ex);
            traceRecordService.finishRun(
                    traceId,
                    STATUS_ERROR,
                    truncateError(cause),
                    new Date(),
                    System.currentTimeMillis() - startMillis
            );
            log.warn("执行流式对话失败", cause);
            // 关闭 SSE 并通知前端错误
            emitter.completeWithError(cause);
        } finally {
            RagTraceContext.clear();
        }
    }

    // 调用原来的 streamChat(...)
    private void invokeTarget(Method method, Object target, Object[] args, SseEmitter emitter) {
        try {
            method.invoke(target, args);
        } catch (Throwable ex) {
            Throwable cause = unwrap(ex);
            log.warn("执行流式对话失败", cause);
            emitter.completeWithError(cause);
        }
    }

    // 解析 InvocationTargetException 中的真实异常
    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getTargetException() != null) {
            return invocationTargetException.getTargetException();
        }
        return throwable;
    }

    // 截断异常信息，防止落库过大变成string
    private String truncateError(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        String message = throwable.getClass().getSimpleName() + ": " + StrUtil.blankToDefault(throwable.getMessage(), "");
        if (message.length() <= ragTraceProperties.getMaxErrorLength()) {
            return message;
        }
        return message.substring(0, ragTraceProperties.getMaxErrorLength());
    }
}
