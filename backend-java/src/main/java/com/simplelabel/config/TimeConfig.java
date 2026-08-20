package com.simplelabel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;

@Configuration
public class TimeConfig {
    @Bean
    Clock simpleLabelClock(@Value("${simplelabel.time-zone:Asia/Shanghai}") String timeZone) {
        try {
            return Clock.system(ZoneId.of(timeZone));
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Invalid SimpleLabel time zone: " + timeZone, exception);
        }
    }
}
