package dev.infusion.core.model.profile

data class Profile(
    val profileId: String,
    val name: String,
    val isDefault: Boolean,
    val profileIndex: Int,
    val usesPrimarySources: Boolean,
    val pinProtected: Boolean,
    val createdAtMs: Long,
) {

    val isPrimary: Boolean get() = isDefault
}
