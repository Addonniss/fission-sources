package dev.infusion.tv.configweb

import dev.infusion.feature.configweb.ConfigApplyResult
import dev.infusion.feature.configweb.ConfigChoiceState
import dev.infusion.feature.configweb.ConfigRowControl
import dev.infusion.feature.configweb.ConfigRowScope
import dev.infusion.feature.configweb.ConfigRowState
import dev.infusion.feature.configweb.ConfigValidationError
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

internal class RowSpec(
    val key: String,
    val label: String,
    val group: String,
    val control: ConfigRowControl,
    val scope: ConfigRowScope = ConfigRowScope.PROFILE,
    val parentKey: String? = null,
    val help: String? = null,
    val min: Int? = null,
    val max: Int? = null,
    val choices: () -> List<ConfigChoiceState> = { emptyList() },
    val active: () -> Boolean = { true },
    val read: () -> String,
    val write: (String) -> ConfigApplyResult,
    val extraRule: (String) -> ConfigValidationError? = { null },
) {

    fun validateValue(token: String): ConfigValidationError? {

        if (parentKey != null && !active()) {
            return if (token == read()) {
                null
            } else {
                ConfigValidationError("value", "this setting is only editable while its parent setting is on")
            }
        }
        val membership = when (control) {
            ConfigRowControl.FLAG -> when (token) {
                "true", "false" -> null
                else -> ConfigValidationError("value", "expected true or false")
            }
            ConfigRowControl.CHOICE ->
                if (choices().any { it.token == token }) {
                    null
                } else {
                    ConfigValidationError("value", "not one of the values this setting accepts")
                }
            ConfigRowControl.NUMBER -> {
                val number = token.toIntOrNull()
                when {
                    number == null -> ConfigValidationError("value", "expected a whole number")
                    min != null && number < min ->
                        ConfigValidationError("value", "below the smallest value this setting accepts")
                    max != null && number > max ->
                        ConfigValidationError("value", "above the largest value this setting accepts")
                    else -> null
                }
            }
        }
        return membership ?: extraRule(token)
    }

    fun apply(token: String): ConfigApplyResult = try {
        write(token)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Throwable) {

        ConfigApplyResult.Failed("the owner could not apply this setting")
    }

    fun state(): ConfigRowState = ConfigRowState(
        key = key,
        label = label,
        group = group,
        control = control,
        value = read(),
        choices = choices(),
        scope = scope,
        parentKey = parentKey,
        active = active(),
        min = min,
        max = max,
        help = help,
    )
}

internal fun verified(read: () -> String, expected: String): ConfigApplyResult =
    if (read() == expected) {
        ConfigApplyResult.Applied
    } else {
        ConfigApplyResult.Failed("the owner did not keep this value")
    }

internal fun flag(value: Boolean): String = if (value) "true" else "false"

internal fun number(value: Int): String = value.toString()

internal fun choicesOf(vararg pairs: Pair<String, String>): List<ConfigChoiceState> =
    pairs.map { (token, label) -> ConfigChoiceState(token, label) }

internal fun awaitUntil(timeoutMs: Long = AWAIT_TIMEOUT_MS, predicate: () -> Boolean): Boolean =
    runBlocking {
        withTimeoutOrNull(timeoutMs) {
            while (!predicate()) {
                delay(AWAIT_POLL_MS)
            }
            true
        } ?: false
    }

private const val AWAIT_TIMEOUT_MS: Long = 5_000
private const val AWAIT_POLL_MS: Long = 20
