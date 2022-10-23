package org.juejin.scg.listener;

import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.client.naming.event.InstancesChangeEvent;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.notify.listener.Subscriber;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cloud.gateway.event.RefreshRoutesEvent;
import org.springframework.cloud.gateway.route.InMemoryRouteDefinitionRepository;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static org.springframework.cloud.loadbalancer.core.CachingServiceInstanceListSupplier.SERVICE_INSTANCE_CACHE_NAME;

@Configuration
public class NacosListener {

    @Autowired
    private NacosConfigManager nacosConfigManager;

    @Autowired
    private RouteDefinitionWriter routeDefinitionWriter;

    @Autowired
    private InMemoryRouteDefinitionRepository routeDefinitionLocator;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private NacosInstancesChangeEventListener nacosInstancesChangeEventListener;

    private static final Logger log = LoggerFactory.getLogger(NacosListener.class);

    private static final String DATA_ID = "gateway-routes";

    private static final String GROUP_ID = "router";


    @PostConstruct
    public void init() throws NacosException {

        nacosConfigListener();

        nacosServiceDiscoveryListener();
    }

    public void nacosConfigListener() throws NacosException {
        log.info("Spring Gateway 开始读取 Nacos 路由配置");

        ConfigService configService = nacosConfigManager.getConfigService();

        if(configService == null){
            throw new RuntimeException("Spring Gateway Nacos 动态路由启动失败");
        }

        String configInfo = configService.getConfig(DATA_ID, GROUP_ID, 100000);

        List<RouteDefinition> definitionList = JacksonUtils.toObj(configInfo, new TypeReference<List<RouteDefinition>>() {});

        for(RouteDefinition definition : definitionList){
            log.info("Spring Gateway Nacos 路由配置 : {}", definition.toString());
            routeDefinitionWriter.save(Mono.just(definition)).block();
        }

        configService.addListener(DATA_ID, GROUP_ID, new Listener() {
            @Override
            public Executor getExecutor() {
                return null;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                // 序列化新路由
                List<RouteDefinition> updateDefinitionList = JacksonUtils.toObj(configInfo, new TypeReference<List<RouteDefinition>>() {});

                // 拿到新路由的所有id
                List<String> ids = updateDefinitionList.stream().map(RouteDefinition::getId).collect(Collectors.toList());

                // 拿到旧路由数据
                Flux<RouteDefinition> routeDefinitions = routeDefinitionLocator.getRouteDefinitions();

                routeDefinitions.doOnNext(r -> {
                    String id = r.getId();
                    if (!ids.contains(id)) {
                        routeDefinitionWriter.delete(Mono.just(id)).subscribeOn(Schedulers.parallel()).subscribe();
                        log.info("Spring Gateway 删除 Nacos 路由配置 : {}", id);
                    }
                }).doOnComplete(() -> {

                }).doAfterTerminate(() -> {
                    for(RouteDefinition definition : updateDefinitionList){
                        log.info("Spring Gateway merge Nacos 路由配置 : {}", definition.toString());
                        routeDefinitionWriter.save(Mono.just(definition)).subscribeOn(Schedulers.parallel()).subscribe();
                    }
                }).subscribe();

                applicationEventPublisher.publishEvent(new RefreshRoutesEvent(new Object()));
            }
        });
    }

    public void nacosServiceDiscoveryListener() {
        // 实例刷新
        NotifyCenter.registerSubscriber(nacosInstancesChangeEventListener);
    }

    @Component
    public static class NacosInstancesChangeEventListener extends Subscriber<InstancesChangeEvent> {
        private static final Logger logger = LoggerFactory.getLogger(NacosInstancesChangeEventListener.class);

        @Resource
        private CacheManager defaultLoadBalancerCacheManager;

        @Override
        public void onEvent(InstancesChangeEvent event) {
            logger.info("Spring Gateway 接收实例刷新事件：{}, 开始刷新缓存", JacksonUtils.toJson(event));
            Cache cache = defaultLoadBalancerCacheManager.getCache(SERVICE_INSTANCE_CACHE_NAME);
            if (cache != null) {
                cache.evict(event.getServiceName());
            }
            logger.info("Spring Gateway 实例刷新完成");

        }

        @Override
        public Class<? extends com.alibaba.nacos.common.notify.Event> subscribeType() {
            return InstancesChangeEvent.class;
        }
    }


}
