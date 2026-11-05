package dev.infusion.core.model

data class NativeItemId(
    val sourceId: SourceId,
    val nativeId: String,
) {
    init {
        require(nativeId.isNotBlank()) { "nativeId must not be blank" }
    }
}
