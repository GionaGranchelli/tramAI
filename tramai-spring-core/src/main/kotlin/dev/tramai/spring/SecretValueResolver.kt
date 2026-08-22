package dev.tramai.spring

import dev.tramai.core.secret.SecretValueResolver

/**
 * Re-exports the core [SecretValueResolver] for Spring backward compatibility if needed,
 * but primarily we should just use the core one.
 *
 * This file is now mostly empty or can be removed if we update all imports.
 */
// Keeping it for now to avoid breaking other files in this module before we update them.
typealias SecretValueResolver = SecretValueResolver
