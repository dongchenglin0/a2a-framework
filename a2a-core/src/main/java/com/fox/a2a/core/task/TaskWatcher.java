package com.fox.a2a.core.task;

import com.fox.a2a.proto.*;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 任务进度监听器
 * 通过 gRPC 流式接口监听任务执行进度，支持取消监听
 */
@Slf4j
public class TaskWatcher {

    /** 任务服务异步 Stub */
    private final TaskServiceGrpc.TaskServiceStub asyncStub;

    /** 是否正在监听 */
    private final AtomicBoolean watching = new AtomicBoolean(false);

    /**
     * 构造函数
     *
     * @param asyncStub 任务服务异步 Stub
     */
    public TaskWatcher(TaskServiceGrpc.TaskServiceStub asyncStub) {
        this.asyncStub = asyncStub;
    }

    /**
     * 开始监听指定任务的进度
     * 通过 watchTask 流式接口持续接收任务事件
     *
     * @param taskId     要监听的任务 ID
     * @param onEvent    收到任务事件时的回调（如进度更新、状态变更）
     * @param onComplete 任务完成时的回调
     * @param onError    发生错误时的回调
     */
    public void watch(String taskId,
                      Consumer<TaskEvent> onEvent,
                      Runnable onComplete,
                      Consumer<Throwable> onError) {
        if (!watching.compareAndSet(false, true)) {
            log.warn("TaskWatcher 已在监听任务 [{}]，忽略重复调用", taskId);
            return;
        }

        log.info("开始监听任务进度，taskId: {}", taskId);

        // 构建监听请求
        WatchTaskRequest request = WatchTaskRequest.newBuilder()
                .setTaskId(taskId)
                .build();

        // 建立流式连接，接收任务事件
        asyncStub.watchTask(request, new StreamObserver<TaskEvent>() {

            @Override
            public void onNext(TaskEvent event) {
                if (!watching.get()) {
                    // 已取消监听，忽略后续事件
                    return;
                }
                log.debug("收到任务事件，taskId: {}，eventType: {}",
                        event.getTaskId(), event.getStatus());
                try {
                    onEvent.accept(event);
                } catch (Exception e) {
                    log.error("处理任务事件异常，taskId: {}", taskId, e);
                }
            }

            @Override
            public void onError(Throwable t) {
                watching.set(false);
                log.error("监听任务 [{}] 时发生错误", taskId, t);
                if (onError != null) {
                    try {
                        onError.accept(t);
                    } catch (Exception e) {
                        log.error("onError 回调异常", e);
                    }
                }
            }

            @Override
            public void onCompleted() {
                watching.set(false);
                log.info("任务 [{}] 监听结束（任务已完成）", taskId);
                if (onComplete != null) {
                    try {
                        onComplete.run();
                    } catch (Exception e) {
                        log.error("onComplete 回调异常", e);
                    }
                }
            }
        });
    }

    /**
     * 取消监听
     * 调用后将不再处理后续任务事件
     */
    public void cancel() {
        if (watching.compareAndSet(true, false)) {
            log.info("TaskWatcher 已取消监听");
        }
    }

    /**
     * 判断是否正在监听
     *
     * @return true 表示监听中
     */
    public boolean isWatching() {
        return watching.get();
    }
}
