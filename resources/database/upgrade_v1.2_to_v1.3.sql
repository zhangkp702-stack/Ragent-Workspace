-- ragent v1.2 -> v1.3 upgrade script
-- Add working-memory task, task turn, and retrieval snapshot tables.

CREATE TABLE IF NOT EXISTS t_conversation_task (
    id                 VARCHAR(20)  NOT NULL PRIMARY KEY,
    conversation_task_id VARCHAR(20)  NOT NULL,
    conversation_id    VARCHAR(20)  NOT NULL,
    user_id            VARCHAR(20)  NOT NULL,
    topic_key          VARCHAR(128),
    goal               VARCHAR(512),
    status             VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    is_active          SMALLINT     NOT NULL DEFAULT 0,
    parent_conversation_task_id VARCHAR(20),
    last_turn_id       VARCHAR(20),
    last_snapshot_id   VARCHAR(20),
    last_message_id    VARCHAR(20),
    turn_count         INTEGER      NOT NULL DEFAULT 0,
    state_json         JSONB,
    last_active_time   TIMESTAMP,
    create_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted            SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_conversation_task_id UNIQUE (conversation_task_id)
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 't_conversation_task'
          AND column_name = 'task_id'
    ) THEN
        ALTER TABLE t_conversation_task RENAME COLUMN task_id TO conversation_task_id;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 't_conversation_task'
          AND column_name = 'parent_task_id'
    ) THEN
        ALTER TABLE t_conversation_task RENAME COLUMN parent_task_id TO parent_conversation_task_id;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = current_schema()
          AND t.relname = 't_conversation_task'
          AND c.conname = 'uk_conversation_task'
    ) THEN
        ALTER TABLE t_conversation_task RENAME CONSTRAINT uk_conversation_task TO uk_conversation_task_id;
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_conversation_active_task ON t_conversation_task (conversation_id, user_id) WHERE is_active = 1 AND deleted = 0;
CREATE INDEX IF NOT EXISTS idx_conversation_task_user_time ON t_conversation_task (conversation_id, user_id, last_active_time);
CREATE INDEX IF NOT EXISTS idx_conversation_task_topic ON t_conversation_task (conversation_id, user_id, topic_key);
CREATE INDEX IF NOT EXISTS idx_conversation_task_state ON t_conversation_task USING gin(state_json);
COMMENT ON TABLE t_conversation_task IS '会话工作记忆任务表';
COMMENT ON COLUMN t_conversation_task.id IS '主键ID';
COMMENT ON COLUMN t_conversation_task.conversation_task_id IS '会话工作记忆任务ID，与流式执行任务ID含义不同';
COMMENT ON COLUMN t_conversation_task.conversation_id IS '会话ID';
COMMENT ON COLUMN t_conversation_task.user_id IS '用户ID';
COMMENT ON COLUMN t_conversation_task.topic_key IS '任务主题标识';
COMMENT ON COLUMN t_conversation_task.goal IS '任务目标';
COMMENT ON COLUMN t_conversation_task.status IS '任务状态：ACTIVE-进行中，FINISHED-已完成，ABANDONED-已放弃';
COMMENT ON COLUMN t_conversation_task.is_active IS '是否当前活跃任务 0：否 1：是';
COMMENT ON COLUMN t_conversation_task.parent_conversation_task_id IS '父会话工作记忆任务ID，用于记录任务切换或派生关系';
COMMENT ON COLUMN t_conversation_task.last_turn_id IS '最近一轮任务记录ID';
COMMENT ON COLUMN t_conversation_task.last_snapshot_id IS '最近一次检索快照ID';
COMMENT ON COLUMN t_conversation_task.last_message_id IS '最近一条关联消息ID';
COMMENT ON COLUMN t_conversation_task.turn_count IS '任务累计轮次数';
COMMENT ON COLUMN t_conversation_task.state_json IS '任务状态JSON，记录工作记忆摘要、槽位、约束等';
COMMENT ON COLUMN t_conversation_task.last_active_time IS '最近活跃时间';
COMMENT ON COLUMN t_conversation_task.create_time IS '创建时间';
COMMENT ON COLUMN t_conversation_task.update_time IS '更新时间';
COMMENT ON COLUMN t_conversation_task.deleted IS '是否删除 0：正常 1：删除';

CREATE TABLE IF NOT EXISTS t_conversation_task_turn (
    id                   VARCHAR(20)  NOT NULL PRIMARY KEY,
    conversation_task_id VARCHAR(20)  NOT NULL,
    conversation_id      VARCHAR(20)  NOT NULL,
    user_id              VARCHAR(20)  NOT NULL,
    user_message_id      VARCHAR(20),
    assistant_message_id VARCHAR(20),
    inheritance_type     VARCHAR(32)  NOT NULL,
    question_text        TEXT         NOT NULL,
    rewrite_question     TEXT,
    retrieval_mode       VARCHAR(32),
    status               VARCHAR(16)  NOT NULL DEFAULT 'RUNNING',
    turn_state_json      JSONB,
    error_message        TEXT,
    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted              SMALLINT     NOT NULL DEFAULT 0
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 't_conversation_task_turn'
          AND column_name = 'task_id'
    ) THEN
        ALTER TABLE t_conversation_task_turn RENAME COLUMN task_id TO conversation_task_id;
    END IF;
END
$$;

ALTER INDEX IF EXISTS idx_task_turn_task_time RENAME TO idx_task_turn_conversation_task_time;
CREATE INDEX IF NOT EXISTS idx_task_turn_conversation_task_time ON t_conversation_task_turn (conversation_task_id, create_time);
CREATE INDEX IF NOT EXISTS idx_task_turn_conversation_time ON t_conversation_task_turn (conversation_id, user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_task_turn_state ON t_conversation_task_turn USING gin(turn_state_json);
COMMENT ON TABLE t_conversation_task_turn IS '会话工作记忆任务轮次表';
COMMENT ON COLUMN t_conversation_task_turn.id IS '主键ID';
COMMENT ON COLUMN t_conversation_task_turn.conversation_task_id IS '会话工作记忆任务ID，与流式执行任务ID含义不同';
COMMENT ON COLUMN t_conversation_task_turn.conversation_id IS '会话ID';
COMMENT ON COLUMN t_conversation_task_turn.user_id IS '用户ID';
COMMENT ON COLUMN t_conversation_task_turn.user_message_id IS '用户消息ID';
COMMENT ON COLUMN t_conversation_task_turn.assistant_message_id IS '助手消息ID';
COMMENT ON COLUMN t_conversation_task_turn.inheritance_type IS '任务继承类型：NEW_TASK-新任务，CONTINUE_ACTIVE-延续当前任务，SWITCH_TASK-切换历史任务';
COMMENT ON COLUMN t_conversation_task_turn.question_text IS '用户原始问题';
COMMENT ON COLUMN t_conversation_task_turn.rewrite_question IS '改写后的问题';
COMMENT ON COLUMN t_conversation_task_turn.retrieval_mode IS '检索模式：FULL-全量检索，INCREMENTAL-增量检索，REUSE-复用历史检索';
COMMENT ON COLUMN t_conversation_task_turn.status IS '轮次状态：RUNNING-处理中，SUCCESS-成功，FAILED-失败';
COMMENT ON COLUMN t_conversation_task_turn.turn_state_json IS '本轮任务状态JSON，记录判断依据、槽位变化等';
COMMENT ON COLUMN t_conversation_task_turn.error_message IS '失败原因';
COMMENT ON COLUMN t_conversation_task_turn.create_time IS '创建时间';
COMMENT ON COLUMN t_conversation_task_turn.update_time IS '更新时间';
COMMENT ON COLUMN t_conversation_task_turn.deleted IS '是否删除 0：正常 1：删除';

CREATE TABLE IF NOT EXISTS t_conversation_retrieval_snapshot (
    id                       VARCHAR(20)  NOT NULL PRIMARY KEY,
    conversation_id          VARCHAR(20)  NOT NULL,
    user_id                  VARCHAR(20)  NOT NULL,
    conversation_task_id     VARCHAR(20)  NOT NULL,
    task_turn_id             VARCHAR(20)  NOT NULL,
    retrieval_mode           VARCHAR(32)  NOT NULL,
    query_text               TEXT,
    rewrite_question         TEXT,
    kb_ids_json              JSONB,
    intent_ids_json          JSONB,
    result_refs_json         JSONB,
    reused_snapshot_ids_json JSONB,
    retrieval_summary        TEXT,
    create_time              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                  SMALLINT     NOT NULL DEFAULT 0
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 't_conversation_retrieval_snapshot'
          AND column_name = 'task_id'
    ) THEN
        ALTER TABLE t_conversation_retrieval_snapshot RENAME COLUMN task_id TO conversation_task_id;
    END IF;
END
$$;

ALTER INDEX IF EXISTS idx_retrieval_snapshot_task_time RENAME TO idx_retrieval_snapshot_conversation_task_time;
CREATE INDEX IF NOT EXISTS idx_retrieval_snapshot_conversation_task_time ON t_conversation_retrieval_snapshot (conversation_task_id, create_time);
CREATE INDEX IF NOT EXISTS idx_retrieval_snapshot_turn ON t_conversation_retrieval_snapshot (task_turn_id);
CREATE INDEX IF NOT EXISTS idx_retrieval_snapshot_conversation_time ON t_conversation_retrieval_snapshot (conversation_id, user_id, create_time);
CREATE INDEX IF NOT EXISTS idx_retrieval_snapshot_refs ON t_conversation_retrieval_snapshot USING gin(result_refs_json);
COMMENT ON TABLE t_conversation_retrieval_snapshot IS '会话检索快照表';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.id IS '主键ID';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.conversation_id IS '会话ID';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.user_id IS '用户ID';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.conversation_task_id IS '会话工作记忆任务ID，与流式执行任务ID含义不同';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.task_turn_id IS '任务轮次ID';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.retrieval_mode IS '检索模式：FULL-全量检索，INCREMENTAL-增量检索，REUSE-复用历史检索';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.query_text IS '实际检索问题';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.rewrite_question IS '改写后的问题';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.kb_ids_json IS '参与检索的知识库ID列表JSON';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.intent_ids_json IS '命中的意图ID列表JSON';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.result_refs_json IS '检索结果引用JSON，记录chunkId、docId、kbId、rank、score、source等';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.reused_snapshot_ids_json IS '复用的历史检索快照ID列表JSON';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.retrieval_summary IS '检索结果摘要';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.create_time IS '创建时间';
COMMENT ON COLUMN t_conversation_retrieval_snapshot.deleted IS '是否删除 0：正常 1：删除';
