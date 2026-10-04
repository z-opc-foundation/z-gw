package com.zifang.z.gw.starter;

import com.zifang.z.gw.core.router.InMemoryRouteRepository;
import com.zifang.z.gw.core.router.RouteMatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ZGatewayAutoConfiguration#routeAutoRefresher} 的注册幂等性。
 *
 * <p>{@code ContextRefreshedEvent} 在 Spring 生命周期里<b>不止可能被发布一次</b>
 * （actuator {@code /refresh}、父子容器重建、测试里反复 refresh），
 * 而 {@code InMemoryRouteRepository.addChangeListener} 往
 * {@code CopyOnWriteArrayList} 里加，<b>不去重</b>。同类的
 * {@code GatewayServerLifecycle} 有 {@code started} 标志防重复启动，
 * 这里原本<b>没有任何幂等保护</b>。
 *
 * <p>后果：事件发几次，路由每次变更就要 refresh 几次，监听器还会持续累积。</p>
 */
class ZGatewayAutoConfigurationRefreshTest {

    @SuppressWarnings("unchecked")
    private static ApplicationListener<ContextRefreshedEvent> refresher(
            InMemoryRouteRepository repo, RouteMatcher matcher) {
        return new ZGatewayAutoConfiguration().routeAutoRefresher(repo, matcher);
    }

    @Test
    @DisplayName("事件重复发布时，change listener 只注册一次")
    void listenerIsRegisteredOnlyOnce() {
        InMemoryRouteRepository repo = mock(InMemoryRouteRepository.class);
        RouteMatcher matcher = mock(RouteMatcher.class);
        ApplicationListener<ContextRefreshedEvent> l = refresher(repo, matcher);

        l.onApplicationEvent(mock(ContextRefreshedEvent.class));
        l.onApplicationEvent(mock(ContextRefreshedEvent.class));
        l.onApplicationEvent(mock(ContextRefreshedEvent.class));

        verify(repo, times(1)).addChangeListener(any());
    }

    @Test
    @DisplayName("首次事件仍要真正刷新一次路由（不能被幂等保护误伤）")
    void firstEventStillRefreshesRoutes() {
        InMemoryRouteRepository repo = mock(InMemoryRouteRepository.class);
        whenRouteDefs(repo);
        RouteMatcher matcher = mock(RouteMatcher.class);

        refresher(repo, matcher).onApplicationEvent(mock(ContextRefreshedEvent.class));

        verify(matcher, times(1)).refresh(any());
    }

    @Test
    @DisplayName("重复事件不会重复 refresh")
    void repeatedEventsDoNotRefreshAgain() {
        InMemoryRouteRepository repo = mock(InMemoryRouteRepository.class);
        whenRouteDefs(repo);
        RouteMatcher matcher = mock(RouteMatcher.class);
        ApplicationListener<ContextRefreshedEvent> l = refresher(repo, matcher);

        l.onApplicationEvent(mock(ContextRefreshedEvent.class));
        l.onApplicationEvent(mock(ContextRefreshedEvent.class));

        verify(matcher, times(1)).refresh(any());
    }

    @SuppressWarnings("unchecked")
    private static void whenRouteDefs(InMemoryRouteRepository repo) {
        org.mockito.Mockito.when(repo.getRouteDefinitions())
                .thenReturn(java.util.Collections.<com.zifang.z.gw.api.RouteDefinition>emptyList());
    }
}
