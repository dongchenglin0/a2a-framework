package main

import (
	"context"
	"log/slog"
	"os"
	"time"

	a2a "github.com/dongchenglin0/a2a-framework/a2a-go-sdk"
)

func main() {
	// 配置协调 Agent
	cfg := a2a.DefaultConfig()
	cfg.AgentID = "go-agent-b"
	cfg.AgentName = "Go协调Agent"
	cfg.AgentType = "coordinator"
	cfg.AgentHost = "localhost"
	cfg.AgentPort = 8022
	cfg.JWTToken = os.Getenv("A2A_JWT_TOKEN")

	agent := a2a.NewAgent(cfg)
	ctx := context.Background()

	// 启动 Agent
	if err := agent.Start(ctx); err != nil {
		slog.Error("启动失败", "error", err)
		os.Exit(1)
	}
	defer agent.Close(ctx)

	slog.Info("Go Agent B 已启动，开始演示...")

	// ─────────────────────────────────────────────
	// 1. 发现 data-processor 类型的 Agent
	// ─────────────────────────────────────────────
	agents, err := agent.Discover(ctx, "data-processor")
	if err != nil {
		slog.Error("发现 Agent 失败", "error", err)
		return
	}
	slog.Info("发现 Agent", "count", len(agents))
	for _, a := range agents {
		slog.Info("  - Agent", "id", a.AgentID, "name", a.AgentName, "host", a.Host, "port", a.Port)
	}

	// ─────────────────────────────────────────────
	// 2. 同步请求：向 go-agent-a 发送求和请求
	// ─────────────────────────────────────────────
	result, err := agent.SendRequest(ctx, "go-agent-a", map[string]any{
		"numbers": []any{1.0, 2.0, 3.0, 4.0, 5.0},
	})
	if err != nil {
		slog.Error("同步请求失败", "error", err)
	} else {
		slog.Info("求和结果", "sum", result["sum"], "count", result["count"])
	}

	// ─────────────────────────────────────────────
	// 3. 单向消息：发送通知
	// ─────────────────────────────────────────────
	msgID, err := agent.Send(ctx, "go-agent-a", "notification", map[string]any{
		"message": "协调Agent已就绪",
		"from":    cfg.AgentID,
	})
	if err != nil {
		slog.Error("单向消息发送失败", "error", err)
	} else {
		slog.Info("单向消息已发送", "messageId", msgID)
	}

	// ─────────────────────────────────────────────
	// 4. 委托任务：排序任务
	// ─────────────────────────────────────────────
	taskID, err := agent.DelegateTask(ctx, "go-agent-a", "sort-task", map[string]any{
		"data": []any{5.0, 3.0, 1.0, 4.0, 2.0},
	})
	if err != nil {
		slog.Error("委托任务失败", "error", err)
	} else {
		slog.Info("任务已委托", "taskID", taskID)

		// 等待一段时间后查询任务状态
		time.Sleep(500 * time.Millisecond)
		taskStatus, err := agent.GetTaskStatus(ctx, taskID)
		if err != nil {
			slog.Error("查询任务状态失败", "error", err)
		} else {
			slog.Info("任务状态", "taskId", taskStatus.TaskID, "status", taskStatus.Status)
		}
	}

	// ─────────────────────────────────────────────
	// 5. 发布 PubSub 事件
	// ─────────────────────────────────────────────
	time.Sleep(time.Second)
	pubMsgID, err := agent.Publish(ctx, "data.events", map[string]any{
		"event":     "processing-complete",
		"agent_id":  cfg.AgentID,
		"timestamp": time.Now().Unix(),
	})
	if err != nil {
		slog.Error("发布事件失败", "error", err)
	} else {
		slog.Info("事件已发布到 data.events", "messageId", pubMsgID)
	}

	slog.Info("Go Agent B 演示完成")
}
