package org.juejin.scg.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.util.List;

@Configuration
public class GatewayApiConfig {
    @Bean
    public CorsWebFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedMethod("*");
        config.addAllowedOrigin("*");
        config.addAllowedHeader("*");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource(new PathPatternParser());
        source.registerCorsConfiguration("/**", config);

        return new CorsWebFilter(source);
    }

    @Bean
    public RouteLocator routeLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route((r) -> r.path("/user/test")
                        .filters(f -> f.requestRateLimiter()
                                .rateLimiter(RedisRateLimiter.class, c -> c.setReplenishRate(1).setBurstCapacity(10).setRequestedTokens(5))
                                .configure(c -> c.setKeyResolver(apiTokenKeyResolver()).setDenyEmptyKey(true)))
                        .uri("lb://user-api"))
//                .route((r) -> r.path("/user/**").uri("lb://user-api"))
                .route((r) -> r.path("/order/**").uri("lb://order-api"))
                .build();
    }

    @Bean
    KeyResolver apiTokenKeyResolver() {
        return exchange -> {
            HttpHeaders headers = exchange.getRequest().getHeaders();
            MultiValueMap<String, String> queryParam = exchange.getRequest().getQueryParams();
            List<String> token = headers.get("token") != null ? headers.get("token") : queryParam.get("token");

            if (token == null || token.isEmpty()) return Mono.empty();

            return Mono.just(token.get(0) + "-" + exchange.getRequest().getURI().getPath());
        };
    }

}
