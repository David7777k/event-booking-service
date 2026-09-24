package io.github.david7777k.seatflow.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class TimeConfiguration {

    /** Injected so tests can decide what "now" is instead of sleeping. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
