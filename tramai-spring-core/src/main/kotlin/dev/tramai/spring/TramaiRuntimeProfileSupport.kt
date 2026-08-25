package dev.tramai.spring

internal object TramaiRuntimeProfileSupport {
    const val PROPERTY_NAME: String = "tramai.profile"

    private val supportedProfiles = linkedSetOf("standard", "sovereign")

    fun validate(configuredProfile: String?) {
        if (configuredProfile == null) {
            return
        }
        require(configuredProfile.lowercase() in supportedProfiles) {
            "Unsupported tramai.profile '$configuredProfile'. Supported values: ${supportedProfiles.joinToString(", ")}."
        }
    }
}
