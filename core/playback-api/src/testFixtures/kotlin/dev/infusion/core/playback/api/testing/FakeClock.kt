package dev.infusion.core.playback.api.testing

class FakeClock(initialEpochMs: Long = 1_700_000_000_000L) {
    var epochMs: Long = initialEpochMs
        private set

    fun advance(ms: Long) {
        require(ms >= 0) { "advance must be >= 0" }
        epochMs += ms
    }
}
