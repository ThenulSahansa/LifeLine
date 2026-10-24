package com.example.lifeline

import java.security.MessageDigest

object Base85 {

    // RFC 1924 / Git Base85 alphabet (contains no colons or quotes)
    private const val B85_ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!#$%&()*+-;<=>?@^_`{|}~"

    /**
     * Computes the SHA-256 hash of a string input and returns 32 raw bytes.
     */
    fun sha256(input: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8))
    }

    /**
     * Encodes arbitrary byte array into RFC 1924 Base85 string.
     * For SHA-256 (32 bytes), outputs exactly 40 characters.
     */
    fun encodeB85(data: ByteArray): String {
        val sb = java.lang.StringBuilder()
        // Process in 4-byte chunks
        for (i in 0 until data.size step 4) {
            val end = (i + 4).coerceAtMost(data.size)
            var value: Long = 0
            for (j in i until end) {
                value = (value shl 8) or (data[j].toLong() and 0xFF)
            }
            // Pad remaining bytes if not a multiple of 4
            val rem = end - i
            if (rem < 4) {
                for (k in rem until 4) {
                    value = (value shl 8)
                }
            }

            val c = CharArray(5)
            for (j in 4 downTo 0) {
                c[j] = B85_ALPHABET[(value % 85).toInt()]
                value /= 85
            }

            // If padded chunk, truncate output accordingly
            if (rem < 4) {
                sb.append(c, 0, rem + 1)
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Encodes byte array into Adobe Ascii85 (chars 33..117: '!' to 'u')
     */
    fun encodeA85(data: ByteArray): String {
        val sb = java.lang.StringBuilder()
        for (i in 0 until data.size step 4) {
            val end = (i + 4).coerceAtMost(data.size)
            var value: Long = 0
            for (j in i until end) {
                value = (value shl 8) or (data[j].toLong() and 0xFF)
            }
            val rem = end - i
            if (rem < 4) {
                for (k in rem until 4) {
                    value = (value shl 8)
                }
            }

            val c = CharArray(5)
            for (j in 4 downTo 0) {
                c[j] = (33 + (value % 85).toInt()).toChar()
                value /= 85
            }

            if (rem < 4) {
                sb.append(c, 0, rem + 1)
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * Generates standard message ID = base85(sha256(NIC + msgNumberStr))
     * e.g. NIC="1234", msgNumberStr="001" -> base85(sha256("1234001"))
     */
    fun generateMessageId(nic: String, msgNumberStr: String): String {
        val hash = sha256(nic + msgNumberStr)
        return encodeB85(hash)
    }

    /**
     * Checks if received ACK hash matches our NIC + message number.
     * Matches both padded ("001") and unpadded ("1") representations
     * in both B85 and A85 encoding variants.
     */
    fun matchesAck(nic: String, msgNumberStr: String, unpaddedNumber: String, rawAck: String): Boolean {
        val cleanAck = rawAck.trim().trim('{', '}', ' ', ':')
        if (cleanAck.isEmpty()) return false

        // Check against padded string ("001")
        val b85Padded = encodeB85(sha256(nic + msgNumberStr))
        if (cleanAck == b85Padded || cleanAck.contains(b85Padded)) return true

        // Check against unpadded string ("1")
        val b85Unpadded = encodeB85(sha256(nic + unpaddedNumber))
        if (cleanAck == b85Unpadded || cleanAck.contains(b85Unpadded)) return true

        // Check Ascii85 variants as fallback
        val a85Padded = encodeA85(sha256(nic + msgNumberStr))
        if (cleanAck == a85Padded || cleanAck.contains(a85Padded)) return true

        val a85Unpadded = encodeA85(sha256(nic + unpaddedNumber))
        if (cleanAck == a85Unpadded || cleanAck.contains(a85Unpadded)) return true

        return false
    }
}
