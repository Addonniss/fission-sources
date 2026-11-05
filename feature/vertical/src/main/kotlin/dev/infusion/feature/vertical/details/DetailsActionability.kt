package dev.infusion.feature.vertical.details

import dev.infusion.feature.vertical.model.PrimaryAction

fun detailsPrimaryAction(
    resumable: Boolean,
    identityEstablished: Boolean,
): PrimaryAction = when {
    resumable -> PrimaryAction.RESUME
    identityEstablished -> PrimaryAction.PLAY
    else -> PrimaryAction.UNPLAYABLE
}
