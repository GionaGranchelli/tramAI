package dev.tramai.spring

import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.context.annotation.Import

/**
 * Enables TramAI's Spring programming model for the active runtime profile.
 *
 * In Spring Boot applications TramAI auto-configuration is normally sufficient,
 * so this annotation is optional. In an annotation-driven Spring context it
 * establishes the application package used for `@AiService` discovery and loads
 * the TramAI profile configurations contributed by modules on the classpath.
 *
 * The annotation does not select a runtime profile. `tramai.profile` remains the
 * authority: missing or `standard` yields the standard runtime, `sovereign`
 * yields the sovereign runtime. There is no implicit sovereign default — the
 * unified starter behaves identically under both profiles.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@AutoConfigurationPackage
@Import(TramaiEnableImportSelector::class)
annotation class EnableTramai
