package no.scienta.cfp;

import java.util.EnumSet;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.filter.UrlHandlerFilter;

/**
 * Keeps what Spring Boot 2.7 did for clients on Spring Boot 4 (specs/spring-boot-4/spec.md).
 */
@Configuration
public class WebConfig {

    /**
     * The legacy web client puts a slash at the end of speaker URLs (specs/speakers/spec.md), and Spring 5 matched a
     * trailing slash on every mapping. Spring 6 stopped matching those, so the slash is dropped before the request is
     * mapped, on every path as before: no redirect, POST /speakers/ stays 201.
     * Ordered after {@link RequestLoggingFilter}, so the log still shows the URL as sent.
     */
    @Bean
    FilterRegistrationBean<UrlHandlerFilter> trailingSlashFilter() {
        FilterRegistrationBean<UrlHandlerFilter> registration = new FilterRegistrationBean<>(
                UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /**
     * spring.servlet.encoding.force only covers the request dispatch in Boot 4, so error bodies came back as plain
     * application/json. Forcing UTF-8 on the error dispatch too keeps application/json;charset=UTF-8 there, as before.
     */
    @Bean
    FilterRegistrationBean<CharacterEncodingFilter> errorDispatchEncodingFilter() {
        CharacterEncodingFilter filter = new CharacterEncodingFilter("UTF-8", true) {
            @Override
            protected boolean shouldNotFilterErrorDispatch() {
                return false;
            }
        };
        FilterRegistrationBean<CharacterEncodingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.ERROR));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }
}
