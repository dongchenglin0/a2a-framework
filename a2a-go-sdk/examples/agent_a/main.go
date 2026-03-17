package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	a2a "github.com/dongchenglin0/a2a-framework/a2a-go-sdk"
)

func main() {
	// 配置 Agent
	cfg := a2a.DefaultConfig()
	cfg.AgentID = "go-agent-a"
	cfg.AgentName = "Go数据处理Agent"
	cfg.AgentType = "data-processor"
	cfg.AgentHost = "localhost"
	cfg.AgentPort = 8021
	cfg.Capabilities = []string{"sum", "sort", "statistics"}
	cfg.JWTToken = os.Getenv("A2A_JWT_TOKEN")

	agent := a2a.NewAgent(cfg)

	// 处理同步请求：对数字列表求和
	agent.OnRequest(func(fromAgentID, messageID string, payload map[string]any) (map[string]any, error) {
		slog.Info("收到同步请求", "from", fromAgentID, "messageId", messageID)
		numbers, _ := payload["numbers"].([]any)
		sum := 0.0
		for _, n := range numbers {
			if v, ok := n.(float64); ok {
				sum += v
			}
		}
		return map[string]any{
			"sum":   sum,
			"count": len(numbers),
		}, nil
	})

	// 处理单向消息
	agent.OnMessage(func(fromAgentID, topic string, payload map[string]any) {
		slog.Info("收到单向消息", "from", fromAgentID, "topic", topic, "payload", payload)
	})

	// 处理任务委托
	agent.OnTask(func(taskID, taskType, delegatorID string, input map[string]any) (map[string]any, error) {
		slog.Info("收到任务", "taskID", taskID, "taskType", taskType, "delegator", delegatorID)
		// 模拟任务处理
		switch taskType {
		case "sort-task":
			data, _ := input["data"].([]any)
			slog.Info("执行排序任务", "dataLen", len(data))
			// 实际排序逻辑可在此实现
			return map[string]any{
				"status": "done",
				"taskId": taskID,
				"sorted": data, // 简化示例，实际应排序
			}, nil
		default:
			return map[string]any{
				"status": "done",
				"taskId": taskID,
			}, nil
		}
	})

	// 订阅 PubSub topic
	agent.Subscribe([]string{"data.events"}, func(topic, publisherID string, payload map[string]any) {
		slog.Info("收到 PubSub 消息", "topic", topic, "from", publisherID, "payload", payload)
	})

	// 启动 Agent
	ctx := context.Background()
	if err := agent.Start(ctx); err != nil {
		slog.Error("启动失败", "error", err)
		os.Exit(1)
	}
	slog.Info("Go Agent A 已启动，等待消息...", "port", cfg.AgentPort)

	// 优雅退出：等待 SIGINT 或 SIGTERM
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	slog.Info("正在关闭 Go Agent A...")
	agent.Close(ctx)
	slog.Info("Go Agent A 已退出")
}
