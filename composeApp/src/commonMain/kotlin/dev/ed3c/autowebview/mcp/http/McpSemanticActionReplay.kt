package dev.ed3c.autowebview.mcp.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds a non-reversible replay identity for an admitted state-changing MCP tool call.
 *
 * JSON-RPC request IDs and source formatting are intentionally absent. The resulting replay key
 * contains only a lowercase SHA-256 digest; authenticated subject, endpoint, and action arguments
 * never enter the replay guard or bridge receipt in raw form.
 */
internal fun semanticActionReplayKey(
    subjectId: String,
    credentialEpoch: String,
    scheme: String,
    authority: String,
    path: String,
    method: String,
    toolName: String,
    arguments: JsonObject,
): McpHttpReplayKey {
    val identity = buildJsonObject {
        put("arguments", arguments)
        put("authority", authority)
        put("credentialEpoch", credentialEpoch)
        put("method", method)
        put("path", path)
        put("scheme", scheme)
        put("subjectId", subjectId)
        put("toolName", toolName)
    }
    return McpHttpReplayKey(sha256Hex(canonicalReplayJson(identity).encodeToByteArray()))
}

/** Deterministic JSON representation: object keys sort recursively and array order is preserved. */
internal fun canonicalReplayJson(element: JsonElement): String = when (element) {
    JsonNull -> "null"
    is JsonObject -> element.entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            "${JsonPrimitive(key)}:${canonicalReplayJson(value)}"
        }
    is JsonArray -> element.joinToString(prefix = "[", postfix = "]", separator = ",") {
        canonicalReplayJson(it)
    }
    is JsonPrimitive -> element.toString()
}

/** Repository-owned common-code SHA-256 used only for bounded replay identities. */
internal fun sha256Hex(input: ByteArray): String {
    val bitLength = input.size.toULong() * 8uL
    val paddedSize = ((input.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedSize)
    input.copyInto(padded)
    padded[input.size] = 0x80.toByte()
    for (index in 0 until 8) {
        padded[padded.lastIndex - index] = ((bitLength shr (index * 8)) and 0xffuL).toByte()
    }

    var h0 = 0x6a09e667u
    var h1 = 0xbb67ae85u
    var h2 = 0x3c6ef372u
    var h3 = 0xa54ff53au
    var h4 = 0x510e527fu
    var h5 = 0x9b05688cu
    var h6 = 0x1f83d9abu
    var h7 = 0x5be0cd19u
    val words = Array(64) { 0u }

    var offset = 0
    while (offset < padded.size) {
        for (index in 0 until 16) {
            val position = offset + index * 4
            words[index] =
                ((padded[position].toInt() and 0xff).toUInt() shl 24) or
                    ((padded[position + 1].toInt() and 0xff).toUInt() shl 16) or
                    ((padded[position + 2].toInt() and 0xff).toUInt() shl 8) or
                    (padded[position + 3].toInt() and 0xff).toUInt()
        }
        for (index in 16 until 64) {
            val s0 = rotateRight(words[index - 15], 7) xor
                rotateRight(words[index - 15], 18) xor
                (words[index - 15] shr 3)
            val s1 = rotateRight(words[index - 2], 17) xor
                rotateRight(words[index - 2], 19) xor
                (words[index - 2] shr 10)
            words[index] = words[index - 16] + s0 + words[index - 7] + s1
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7

        for (index in 0 until 64) {
            val sum1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val choose = (e and f) xor (e.inv() and g)
            val temporary1 = h + sum1 + choose + SHA256_ROUND_CONSTANTS[index] + words[index]
            val sum0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temporary2 = sum0 + majority

            h = g
            g = f
            f = e
            e = d + temporary1
            d = c
            c = b
            b = a
            a = temporary1 + temporary2
        }

        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += h
        offset += 64
    }

    return listOf(h0, h1, h2, h3, h4, h5, h6, h7).joinToString(separator = "") {
        it.toLong().toString(16).padStart(8, '0')
    }
}

private fun rotateRight(value: UInt, bitCount: Int): UInt =
    (value shr bitCount) or (value shl (32 - bitCount))

private val SHA256_ROUND_CONSTANTS = arrayOf(
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u,
    0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
    0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
    0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
    0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
    0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
    0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
    0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
    0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
    0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u,
    0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u,
    0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
    0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
    0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
)
