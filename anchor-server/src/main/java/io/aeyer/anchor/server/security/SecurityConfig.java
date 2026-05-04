package io.aeyer.anchor.server.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public AnchorApiTokenFilter anchorApiTokenFilter(
            @Value("${anchor.api-token:}") String apiToken) {
        AnchorApiTokenFilter filter = new AnchorApiTokenFilter(apiToken);
        if (filter.isAuthEnabled()) {
            log.info("API token auth ENABLED — Bearer required on protected paths.");
        } else {
            log.info("API token auth DISABLED — set ANCHOR_API_TOKEN to require Bearer auth.");
        }
        return filter;
    }

    /**
     * Register the filter explicitly so we can control ordering and the
     * URL pattern set. Spring's auto-registration would also work for a
     * @Component-annotated filter but this is more obviously deliberate.
     */
    @Bean
    public FilterRegistrationBean<AnchorApiTokenFilter> anchorApiTokenFilterRegistration(
            AnchorApiTokenFilter filter) {
        FilterRegistrationBean<AnchorApiTokenFilter> bean = new FilterRegistrationBean<>(filter);
        bean.addUrlPatterns("/*");
        bean.setOrder(0); // run before anything else
        bean.setName("anchorApiTokenFilter");
        return bean;
    }
}
