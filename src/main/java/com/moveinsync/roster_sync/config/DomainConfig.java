package com.moveinsync.roster_sync.config;

import com.moveinsync.roster_sync.domain.EventApplier;
import com.moveinsync.roster_sync.domain.RecordValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DomainConfig {

    @Bean
    RecordValidator recordValidator() {
        return new RecordValidator();
    }

    @Bean
    EventApplier eventApplier() {
        return new EventApplier();
    }
}