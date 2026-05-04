package dev.tramai.dashboard

/**
 * Marker class for the Tramai Dashboard module.
 *
 * Used by [DashboardAutoConfiguration] as a [ConditionalOnClass] guard so that
 * the auto-configuration only activates when the dashboard module is on the classpath.
 */
class DashboardMarker
