package com.fox.a2a.registry.provider;

import com.fox.a2a.proto.AgentInfo;
import com.fox.a2a.proto.AgentStatus;

import java.util.List;
import java.util.Optional;

/**
 * 注册中心顶层接口
 * 所有注册中心实现必须实现此接口
 * 支持通过 SPI 或配置动态切换实现
 *
 * <p>架构说明：
 * <pre>
 * RegistryProvider（注册中心顶层接口）
 *     ├── AbstractRegistryProvider（公共逻辑基类）
 *     │   ├── MemoryRegistryProvider（内存实现，包装 InMemoryAgentStore）
 *     │   ├── RedisRegistryProvider（Redis 实现，包装 RedisAgentStore）
 *     │   ├── NacosRegistryProvider（Nacos 3.x 实现）
 *     │   └── EtcdRegistryProvider（ETCD 实现）
 *     └── RegistryProviderFactory（工厂类，根据配置创建实例）
 * </pre>
 */
public interface RegistryProvider {

    /**
     * 提供者名称，用于配置识别
     * 对应 a2a.registry.provider 配置值，如 "memory"/"redis"/"nacos"/"etcd"
     */
    String getName();

    /**
     * 初始化注册中心
     * 建立连接、加载配置等初始化操作
     */
    void initialize();

    /**
     * 销毁注册中心
     * 释放连接、清理资源等销毁操作
     */
    void destroy();

    /**
     * 检查注册中心是否健康
     *
     * @return true 表示连接正常，false 表示连接异常
     */
    boolean isHealthy();

    // ===== Agent 生命周期 =====

    /**
     * 注册 Agent
     *
     * @param agentInfo Agent 信息
     */
    void register(AgentInfo agentInfo);

    /**
     * 注销 Agent
     *
     * @param agentId Agent 唯一标识
     */
    void deregister(String agentId);

    /**
     * 心跳更新
     * 更新 Agent 的最后心跳时间和状态
     *
     * @param agentId         Agent 唯一标识
     * @param status          当前状态
     * @param timestampMillis 心跳时间戳（毫秒）
     */
    void heartbeat(String agentId, AgentStatus status, long timestampMillis);

    // ===== 查询 =====

    /**
     * 根据 ID 查询 Agent
     *
     * @param agentId Agent 唯一标识
     * @return Agent 信息（Optional）
     */
    Optional<AgentInfo> findById(String agentId);

    /**
     * 查询所有 Agent
     *
     * @return Agent 列表
     */
    List<AgentInfo> findAll();

    /**
     * 按类型查询 Agent
     *
     * @param agentType Agent 类型
     * @return Agent 列表
     */
    List<AgentInfo> findByType(String agentType);

    /**
     * 按能力查询 Agent
     *
     * @param capabilities 能力列表（满足任意一个即可）
     * @return Agent 列表
     */
    List<AgentInfo> findByCapabilities(List<String> capabilities);

    /**
     * 按状态查询 Agent
     *
     * @param status Agent 状态
     * @return Agent 列表
     */
    List<AgentInfo> findByStatus(AgentStatus status);

    /**
     * 发现可用 Agent
     * 综合按类型、能力过滤，只返回 ONLINE 状态的 Agent
     *
     * @param agentType    Agent 类型（可为 null）
     * @param capabilities 能力列表（可为 null）
     * @param limit        最大返回数量（0 表示不限制）
     * @return 可用 Agent 列表
     */
    List<AgentInfo> discover(String agentType, List<String> capabilities, int limit);

    // ===== 状态管理 =====

    /**
     * 更新 Agent 状态
     *
     * @param agentId Agent 唯一标识
     * @param status  新状态
     */
    void updateStatus(String agentId, AgentStatus status);

    /**
     * 获取 Agent 最后心跳时间
     *
     * @param agentId Agent 唯一标识
     * @return 最后心跳时间戳（毫秒），0 表示未记录
     */
    long getLastHeartbeat(String agentId);

    /**
     * 检查 Agent 是否存在
     *
     * @param agentId Agent 唯一标识
     * @return true 表示存在
     */
    boolean exists(String agentId);

    /**
     * 获取 Agent 总数
     *
     * @return Agent 数量
     */
    int count();

    // ===== 事件监听（可选实现） =====

    /**
     * 注册 Agent 变更监听器
     * 支持注册中心主动推送（如 Nacos 监听、ETCD Watch）
     * 默认为空实现，子类可按需覆盖
     *
     * @param listener 事件监听器
     */
    default void addListener(RegistryEventListener listener) {
    }

    /**
     * 移除 Agent 变更监听器
     * 默认为空实现，子类可按需覆盖
     *
     * @param listener 事件监听器
     */
    default void removeListener(RegistryEventListener listener) {
    }
}
