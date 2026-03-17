package a2a

import (
	"context"
	"io"
	"log/slog"
	"math"
	"time"

	pb "github.com/dongchenglin0/a2a-framework/a2a-go-sdk/proto"
)

// PubSubSubscriber PubSub 订阅者，支持断线自动重连
type PubSubSubscriber struct {
	stub         pb.PubSubServiceClient
	agentID      string
	sessionToken string
	cancel       context.CancelFunc
}

// NewPubSubSubscriber 创建 PubSub 订阅者
func NewPubSubSubscriber(stub pb.PubSubServiceClient, agentID, sessionToken string) *PubSubSubscriber {
	return &PubSubSubscriber{
		stub:         stub,
		agentID:      agentID,
		sessionToken: sessionToken,
	}
}

// Subscribe 订阅指定 topics，消息到达时调用 handler，断线后自动重连
func (p *PubSubSubscriber) Subscribe(topics []string, handler PubSubHandler) {
	ctx, cancel := context.WithCancel(context.Background())
	p.cancel = cancel
	go p.subscribeLoop(ctx, topics, handler)
}

// subscribeLoop 订阅主循环，负责断线重连逻辑
func (p *PubSubSubscriber) subscribeLoop(ctx context.Context, topics []string, handler PubSubHandler) {
	attempt := 0
	for {
		// 检查是否已取消
		select {
		case <-ctx.Done():
			return
		default:
		}

		err := p.doSubscribe(ctx, topics, handler)

		// 如果是主动取消，直接退出
		if ctx.Err() != nil {
			slog.Info("PubSub 订阅已取消", "agentId", p.agentID)
			return
		}

		// 指数退避重连，最大等待 30 秒
		waitSec := math.Min(math.Pow(2, float64(attempt)), 30)
		wait := time.Duration(waitSec) * time.Second
		slog.Warn("PubSub 连接断开，准备重连",
			"wait", wait,
			"attempt", attempt,
			"error", err,
		)

		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
		attempt++
	}
}

// doSubscribe 建立一次订阅流，持续接收消息直到断开
func (p *PubSubSubscriber) doSubscribe(ctx context.Context, topics []string, handler PubSubHandler) error {
	stream, err := p.stub.Subscribe(ctx, &pb.PubSubSubscribeRequest{
		AgentId:      p.agentID,
		Topics:       topics,
		SessionToken: p.sessionToken,
	})
	if err != nil {
		return err
	}

	slog.Info("PubSub 订阅已建立", "agentId", p.agentID, "topics", topics)

	for {
		msg, err := stream.Recv()
		if err == io.EOF {
			// 服务端正常关闭流
			return nil
		}
		if err != nil {
			return err
		}
		// 解析 payload 并调用处理器
		payload := StructToMap(msg.Payload)
		handler(msg.Topic, msg.PublisherAgentId, payload)
	}
}

// Unsubscribe 取消订阅，停止接收消息
func (p *PubSubSubscriber) Unsubscribe() {
	if p.cancel != nil {
		p.cancel()
	}
}
