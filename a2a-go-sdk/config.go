package a2a

// Config A2A 客户端配置
type Config struct {
	// Registry 地址
	RegistryHost string // 默认: "localhost"
	RegistryPort int    // 默认: 9090

	// 本 Agent 信息
	AgentID      string
	AgentName    string
	AgentType    string
	AgentHost    string // 对外暴露的 gRPC host
	AgentPort    int    // 对外暴露的 gRPC port
	Capabilities []string
	Metadata     map[string]string

	// 认证
	JWTToken string

	// 连接配置
	ConnectTimeoutMs    int  // 默认: 5000
	RequestTimeoutMs    int  // 默认: 30000
	HeartbeatIntervalMs int  // 默认: 10000
	AutoRegister        bool // 默认: true
}

// DefaultConfig 返回默认配置
func DefaultConfig() Config {
	return Config{
		RegistryHost:        "localhost",
		RegistryPort:        9090,
		AgentHost:           "localhost",
		ConnectTimeoutMs:    5000,
		RequestTimeoutMs:    30000,
		HeartbeatIntervalMs: 10000,
		AutoRegister:        true,
	}
}
