package io.github.david7777k.seatflow.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    /**
     * Time is injected rather than read from {@code Instant.now()} at the call
     * site, so tests can decide what "now" is.
     *
     * <p>This service turns on time in two places - whether a hold has run out
     * and whether sales are open - and testing either by sleeping would make the
     * suite slow and flaky.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
