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

package com.nageoffer.ai.ragent.infra.chat;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 流式首包探测桥接器，因为模型出错之后，可以切换模型，避免出错之后前端先显示出错，然后又显示正常内容。
 * <p>
 * 这个桥接器的作用是，在模型调用onContent、onThinking、onComplete、onError 方法时，将结果缓存起来，直到首包探测结果返回。
 * 如果首包探测结果是 SUCCESS，就提交缓冲存，否则就直接通知下游。
 * <p>
 * 这个桥接器的实现是基于 CompletableFuture 的，所以可以阻塞等待首包探测结果，也可以在首包探测结果返回后，立即提交缓冲存。
 */
final class ProbeStreamBridge implements StreamCallback {

    // 原始的前端回调接口
    private final StreamCallback downstream;
    // 用来存储当前调用的结果，等待首包探测结果返回
    private final CompletableFuture<ProbeResult> probe = new CompletableFuture<>();
    // 一个锁对象，用来保护缓冲存的线程安全访问
    private final Object lock = new Object();
    // 不是直接缓存字符串，而是缓存一个个 Runnable。
    private final List<Runnable> buffer = new ArrayList<>();
    // 表示当前模型是否已经被确认可用，false：当前模型还没有通过首包探测，回调事件先缓存起来，true：当前模型已经通过首包探测，回调事件直接通知下游
    private volatile boolean committed;

    ProbeStreamBridge(StreamCallback downstream) {
        this.downstream = downstream;
    }

    @Override
    public void onContent(String content) {
        // 只要模型调用了onContent，说明有内容返回，首包探测结果标记为成功。
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onContent(content));
    }
    // 表示模型返回的是“思考内容”
    @Override
    public void onThinking(String content) {
        probe.complete(ProbeResult.success());
        bufferOrDispatch(() -> downstream.onThinking(content));
    }

    @Override
    public void onComplete() {
        probe.complete(ProbeResult.noContent());
        bufferOrDispatch(downstream::onComplete);
    }

    @Override
    public void onError(Throwable t) {
        probe.complete(ProbeResult.error(t));
        bufferOrDispatch(() -> downstream.onError(t));
    }

    /**
     * 阻塞等待首包探测结果，SUCCESS 时自动提交缓冲
     */
    ProbeResult awaitFirstPacket(long timeout, TimeUnit unit) throws InterruptedException {
        ProbeResult result;
        try {
            // 阻塞等待首包结果，也就是上面的oncontent还有onthinking方法调用结果
            result = probe.get(timeout, unit);
        } catch (TimeoutException e) {
            return ProbeResult.timeout();
        } catch (ExecutionException e) {
            return ProbeResult.error(e.getCause());
        }

        // 如果首包探测结果是 SUCCESS，就提交缓冲存，否则就直接通知下游
        if (result.isSuccess()) {
            commit();
        }
        return result;
    }

    private void commit() {
        synchronized (lock) {
            // 判断是否已经提交过，如果已经提交过，就直接返回
            if (committed) {
                return;
            }
            // 如果没有提交过，就提交缓冲存
            committed = true;
            buffer.forEach(Runnable::run);
        }
    }

    // 根据当前模型是否已经被确认可用，来判断是缓存还是直接通知下游
    private void bufferOrDispatch(Runnable action) {
        boolean dispatchNow;
        synchronized (lock) {
            dispatchNow = committed;
            // 如果当前模型还没有通过首包探测，就缓存起来
            if (!dispatchNow) {
                buffer.add(action);
            }
        }
        // 如果当前模型已经通过首包探测，就直接通知下游
        if (dispatchNow) {
            action.run();
        }
    }

    /**
     * 探测结果
     */
    @Getter
    static class ProbeResult {

        enum Type {SUCCESS, ERROR, TIMEOUT, NO_CONTENT}

        // 记录当前探测结果类型。
        private final Type type;
        // 记录当前探测结果的异常信息。
        private final Throwable error;

        private ProbeResult(Type type, Throwable error) {
            this.type = type;
            this.error = error;
        }

        static ProbeResult success() {
            return new ProbeResult(Type.SUCCESS, null);
        }

        static ProbeResult error(Throwable t) {
            return new ProbeResult(Type.ERROR, t);
        }

        static ProbeResult timeout() {
            return new ProbeResult(Type.TIMEOUT, null);
        }

        static ProbeResult noContent() {
            return new ProbeResult(Type.NO_CONTENT, null);
        }

        boolean isSuccess() {
            return type == Type.SUCCESS;
        }
    }
}
