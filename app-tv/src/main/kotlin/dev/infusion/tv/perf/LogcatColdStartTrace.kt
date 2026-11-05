package dev.infusion.tv.perf

class LogcatColdStartTrace(
    private val tag: String = DEFAULT_TAG,
) {

    fun trace(stage: String, detail: String = "") {
        android.util.Log.d(
            tag,
            if (detail.isEmpty()) "coldstart stage=$stage" else "coldstart stage=$stage $detail",
        )
    }

    companion object {

        const val DEFAULT_TAG: String = "INFUSION_PERF"

        fun digest(key: String): String {
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray(Charsets.UTF_8))
            return bytes.take(4).joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
