package io.aurora.spring

import org.springframework.context.annotation.Import

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Import(AuroraAutoConfiguration::class)
annotation class EnableAurora
