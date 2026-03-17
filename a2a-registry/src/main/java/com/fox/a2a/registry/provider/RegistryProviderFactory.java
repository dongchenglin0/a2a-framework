package com.fox.a2a.registry.provider;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 注册中心提供者工厂
 * 管理所有 RegistryProvider 实现，提供统一访问入口
 * Spring 自动注入所有 RegistryProvider Bean
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistryProviderFactory {

    /** Spring 自动注入所有 RegistryProvider 实现 */
    private final List<RegistryProvider> providers;

    private Map<String, RegistryProvider> providerMap;

    @PostConstruct
    public void init() {
        providerMap = providers.stream()
            .collect(Collectors.toMap(RegistryProvider::getName, Function.identity()));
        log.info("已加载注册中心提供者: {}", providerMap.keySet());

        // 初始化所有提供者
        providers.forEach(p -> {
            try {
                p.initialize();
                log.info("注册中心提供者初始化成功: {}", p.getName());
            } catch (Exception e) {
                log.error("注册中心提供者初始化失败: {}", p.getName(), e);
            }
        });
    }

    @PreDestroy
    public void destroy() {
        providers.forEach(p -> {
            try {
                p.destroy();
            } catch (Exception e) {
                log.warn("注册中心提供者销毁异常: {}", p.getName(), e);
            }
        });
    }

    /**
     * 获取指定名称的提供者
     *
     * @param name 提供者名称（memory/redis/nacos/etcd）
     * @return RegistryProvider 实例
     * @throws IllegalArgumentException 未找到时抛出
     */
    public RegistryProvider getProvider(String name) {
        RegistryProvider provider = providerMap.get(name);
        if (provider == null) {
            throw new IllegalArgumentException(
                "未找到注册中心提供者: " + name + "，可用提供者: " + providerMap.keySet());
        }
        return provider;
    }

    /**
     * 获取当前活跃的提供者（第一个健康的）
     * 用于故障转移场景
     *
     * @return 健康的 RegistryProvider
     * @throws IllegalStateException 没有可用提供者时抛出
     */
    public RegistryProvider getActiveProvider() {
        return providers.stream()
            .filter(RegistryProvider::isHealthy)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "没有可用的注册中心提供者，已检查: " + providerMap.keySet()));
    }

    /**
     * 获取所有提供者名称
     */
    public List<String> getProviderNames() {
        return providers.stream().map(RegistryProvider::getName).toList();
    }

    /**
     * 获取所有提供者的健康状态
     */
    public Map<String, Boolean> getHealthStatus() {
        return providers.stream()
            .collect(Collectors.toMap(
                RegistryProvider::getName,
                p -> {
                    try { return p.isHealthy(); }
                    catch (Exception e) { return false; }
                }
            ));
    }
}
