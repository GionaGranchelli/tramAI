package dev.tramai.spring.sovereign.ops.rest

import org.springframework.boot.SpringBootConfiguration

/**
 * Test configuration to satisfy @WebMvcTest scanning requirements.
 *
 * NOTE: Deliberately does NOT carry @EnableAutoConfiguration because it
 * overrides the @WebMvcTest slice, causing controllers to be bypassed by
 * the auto-configured ResourceHttpRequestHandler (observed on CI). The
 * @WebMvcTest annotation already enables Web MVC auto-configuration as
 * part of its slice; additional @EnableAutoConfiguration here would add
 * all auto-configuration classes back in, defeating the slice.
 */
@SpringBootConfiguration
class TestRestModuleConfig
