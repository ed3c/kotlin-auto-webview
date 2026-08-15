package dev.ed3c.autowebview.domain

object StableIds {
    fun from(vararg parts: String): String {
        val input = parts.joinToString("|")
        var hash = 0xcbf29ce484222325uL
        for (char in input) {
            hash = hash xor char.code.toULong()
            hash *= 0x100000001b3uL
        }
        return hash.toString(16).padStart(16, '0')
    }
}
