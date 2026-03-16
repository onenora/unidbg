package com.mengying.fqnovel.service;

import com.mengying.fqnovel.config.FQDownloadProperties;
import com.mengying.fqnovel.utils.ProcessLifecycle;
import com.mengying.fqnovel.utils.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Service
public class AutoRestartService {

    private static final Logger log = LoggerFactory.getLogger(AutoRestartService.class);
    private static final String REASON_PREFIX_AUTO_RESTART = "AUTO_RESTART:";
    private static final String REASON_PREFIX_AUTO_SELF_HEAL = "AUTO_SELF_HEAL:";

    private final FQDownloadProperties downloadProperties;
    private final FQDeviceRotationService deviceRotationService;
    private final FQRegisterKeyService registerKeyService;
    private final ScheduledExecutorService restartExecutor =
        Executors.newScheduledThreadPool(2, new NamedDaemonThreadFactory("auto-restart-"));

    public AutoRestartService(
        FQDownloadProperties downloadProperties,
        FQDeviceRotationService deviceRotationService,
        FQRegisterKeyService registerKeyService
    ) {
        this.downloadProperties = downloadProperties;
        this.deviceRotationService = deviceRotationService;
        this.registerKeyService = registerKeyService;
    }

    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicBoolean healing = new AtomicBoolean(false);
    private volatile long windowStartMs = 0L;
    private volatile long lastRestartAtMs = 0L;
    private volatile long lastSelfHealAtMs = 0L;
    private final AtomicBoolean restarting = new AtomicBoolean(false);

    public synchronized void recordSuccess() {
        errorCount.set(0);
        windowStartMs = 0L;
        restarting.set(false);
    }

    public void recordFailure(String reason) {
        if (!downloadProperties.getAutoRestart().isEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        int threshold = autoRestartThreshold();

        int count;
        synchronized (this) {
            long windowMs = autoRestartWindowMs();
            long start = windowStartMs;
            if (start == 0L || now - start > windowMs) {
                windowStartMs = now;
                errorCount.set(0);
            }
            count = errorCount.incrementAndGet();
        }

        if (count < threshold) {
            return;
        }

        if (restarting.get()) {
            return;
        }

        if (trySelfHeal(reason, now, count, threshold)) {
            return;
        }

        long minIntervalMs = autoRestartMinIntervalMs();
        if (now - lastRestartAtMs < minIntervalMs) {
            return;
        }
        lastRestartAtMs = now;

        // 使用 CAS 确保只有一个线程能进入重启流程
        if (!restarting.compareAndSet(false, true)) {
            return;
        }

        ProcessLifecycle.markShuttingDown(prefixedReason(REASON_PREFIX_AUTO_RESTART, reason));

        log.error("连续异常达到阈值，准备退出进程触发重启: count={}, threshold={}, reason={}", count, threshold, reason);
        int exitCode = 2;
        long exitDelayMs = autoRestartExitDelayMs();
        restartExecutor.schedule(() -> {
            try {
                System.exit(exitCode);
            } catch (Throwable t) {
                Runtime.getRuntime().halt(exitCode);
            }
        }, exitDelayMs, TimeUnit.MILLISECONDS);

        long forceHaltAfterMs = autoRestartForceHaltAfterMs();
        if (forceHaltAfterMs > 0) {
            restartExecutor.schedule(() -> {
                // 如果 System.exit 因 shutdown hook 卡住，这里会强制结束，保证 Docker/systemd 能拉起
                log.error("System.exit 未能在期望时间内退出，强制 halt 结束进程: exitCode={}, waitedMs={}", exitCode, forceHaltAfterMs);
                Runtime.getRuntime().halt(exitCode);
            }, forceHaltAfterMs, TimeUnit.MILLISECONDS);
        }
    }

    private boolean trySelfHeal(String reason, long now, int count, int threshold) {
        if (!downloadProperties.getAutoRestart().isSelfHealEnabled()) {
            return false;
        }

        if (healing.get()) {
            return true;
        }

        long cooldownMs = selfHealCooldownMs();
        if (cooldownMs > 0 && now - lastSelfHealAtMs < cooldownMs) {
            return false;
        }
        if (!healing.compareAndSet(false, true)) {
            return true;
        }
        lastSelfHealAtMs = now;

        log.warn("连续异常达到阈值，优先尝试自愈（重置签名服务 / 切换设备）: count={}, threshold={}, reason={}", count, threshold, reason);
        String selfHealReason = prefixedReason(REASON_PREFIX_AUTO_SELF_HEAL, reason);

        // 自愈逻辑放后台线程，避免阻塞当前业务线程；失败也不影响后续退回到 auto-restart。
        restartExecutor.execute(() -> {
            boolean resetOk = false;
            boolean invalidateKeyOk = false;
            boolean rotateOk = false;
            try {
                resetOk = runSelfHealStep("请求重置签名服务失败", () -> {
                    FQEncryptServiceWorker.requestGlobalReset(selfHealReason);
                    return true;
                });
                invalidateKeyOk = runSelfHealStep("失效当前 registerkey 失败", () -> {
                    registerKeyService.invalidateCurrentKey();
                    return true;
                });
                rotateOk = runSelfHealStep("切换设备失败", () ->
                    deviceRotationService.forceRotate(selfHealReason)
                );
            } finally {
                healing.set(false);
                if (resetOk && invalidateKeyOk) {
                    recordSuccess();
                    if (!rotateOk) {
                        log.warn("自愈未完成设备切换，但签名服务/registerkey 已刷新成功: rotateOk={}", rotateOk);
                    }
                } else {
                    log.warn("自愈未完全成功，保留错误计数以便必要时触发重启: resetOk={}, invalidateKeyOk={}, rotateOk={}",
                        resetOk, invalidateKeyOk, rotateOk);
                }
            }
        });

        return true;
    }

    private static String prefixedReason(String prefix, String reason) {
        return prefix + Texts.nullToEmpty(reason);
    }

    private int autoRestartThreshold() {
        return Math.max(1, downloadProperties.getAutoRestart().getErrorThreshold());
    }

    private long autoRestartWindowMs() {
        return Math.max(1L, downloadProperties.getAutoRestart().getWindowMs());
    }

    private long autoRestartMinIntervalMs() {
        return Math.max(0L, downloadProperties.getAutoRestart().getMinIntervalMs());
    }

    private long autoRestartExitDelayMs() {
        return Math.max(0L, downloadProperties.getAutoRestart().getExitDelayMs());
    }

    private long autoRestartForceHaltAfterMs() {
        return Math.max(0L, downloadProperties.getAutoRestart().getForceHaltAfterMs());
    }

    private long selfHealCooldownMs() {
        return Math.max(0L, downloadProperties.getAutoRestart().getSelfHealCooldownMs());
    }

    private static boolean runSelfHealStep(String message, Supplier<Boolean> action) {
        if (action == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(action.get());
        } catch (Throwable t) {
            log.warn("自愈：" + message, t);
            return false;
        }
    }

    @PreDestroy
    public void destroy() {
        restartExecutor.shutdownNow();
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger seq = new AtomicInteger(0);

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
