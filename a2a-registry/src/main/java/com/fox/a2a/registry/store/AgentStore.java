package com.fox.a2a.registry.store;

import com.fox.a2a.proto.AgentInfo;
import com.fox.a2a.proto.AgentStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 存储接口
 * 定义 Agent 信息的 CRUD 及查询操作
 */
public interface AgentStore {

    /**
     * 保存 Agent 信息
     *
     * @param agentInfo Agent 信息（protobuf 对象）
     */
    void save(AgentInfo agentInfo);

    /**
     * 根据 agentId 查询 Agent
     *
     * @param agentId Agent 唯一标识
     * @return Optional 包装的 AgentInfo
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
     * @return 匹配的 Agent 列表
     */
    List<AgentInfo> findByType(String agentType);

    /**
     * 按能力列表查询 Agent（包含任意一个能力即匹配）
     *
     * @param capabilities 能力列表
     * @return 匹配的 Agent 列表
     */
    List<AgentInfo> findByCapabilities(List<String> capabilities);

    /**
     * 按状态查询 Agent
     *
     * @param status Agent 状态
     * @return 匹配的 Agent 列表
     */
    List<AgentInfo> findByStatus(AgentStatus status);

    /**
     * 更新 Agent 状态
     *
     * @param agentId Agent 唯一标识
     * @param status  新状态
     */
    void updateStatus(String agentId, AgentStatus status);

    /**
     * 更新 Agent 心跳时间戳
     *
     * @param agentId         Agent 唯一标识
     * @param timestampMillis 心跳时间戳（毫秒）
     */
    void updateHeartbeat(String agentId, long timestampMillis);

    /**
     * 获取 Agent 最后心跳时间戳
     *
     * @param agentId Agent 唯一标识
     * @return 最后心跳时间戳（毫秒），不存在则返回 0
     */
    long getLastHeartbeat(String agentId);

    /**
     * 移除 Agent
     *
     * @param agentId Agent 唯一标识
     */
    void remove(String agentId);

    /**
     * 判断 Agent 是否存在
     *
     * @param agentId Agent 唯一标识
     * @return 存在返回 true
     */
    boolean exists(String agentId);

    /**
     * 获取 Agent 总数
     *
     * @return Agent 数量
     */
    int count();
}
