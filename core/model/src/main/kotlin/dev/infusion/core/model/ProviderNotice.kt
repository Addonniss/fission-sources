package dev.infusion.core.model

data class ProviderNotice(
    val providerName: String,
    val title: String,
    val body: String? = null,
) {
    init {
        require(providerName.isNotBlank()) { "providerName must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
    }
}