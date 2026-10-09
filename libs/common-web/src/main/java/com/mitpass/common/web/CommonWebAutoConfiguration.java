package com.mitpass.common.web;

import com.mitpass.common.web.correlation.CorrelationIdFilter;
import com.mitpass.common.web.error.GlobalExceptionHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registered in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}: a servlet
 * service gets ProblemDetail errors and correlation ids just by depending on {@code common-web}.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }
}
