package com.isles.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 实时推送的"提交后执行"辅助（docs/00 §3.2：事务内禁止网络调用）。
 *
 * <p>业务写事务成功后（afterCommit）再推送；推送异常只记录日志、不影响已提交业务。
 * 客户端断线重连后通过 REST 按游标补拉，因此推送丢失不影响数据完整性（docs/02 §5.4）。
 */
@Component
public class RealtimeDispatch {

    private static final Logger log = LoggerFactory.getLogger(RealtimeDispatch.class);

    /**
     * 在当前事务提交后执行推送；无事务时立即执行（便于单元测试与只读场景）。
     */
    public void execute(Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runQuietly(action);
                }
            });
        } else {
            runQuietly(action);
        }
    }

    private void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            // 推送是尽力而为通道：失败只记日志，不回滚/不重试业务事务
            log.warn("实时推送失败，客户端将按 REST 游标补拉兜底", ex);
        }
    }
}
