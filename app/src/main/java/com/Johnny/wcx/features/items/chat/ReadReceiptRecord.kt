package com.Johnny.wcx.features.items.chat

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val MAX_THIRD_PARTY_ENDPOINT_LENGTH = 2048

internal fun normalizeThirdPartyReadReceiptEndpoint(value: String): String? {
    if (value.length > MAX_THIRD_PARTY_ENDPOINT_LENGTH) return null
    val trimmed = value.trimEnd('/')
    if (
        trimmed.isBlank() || trimmed != trimmed.trim() || trimmed.any(Char::isWhitespace)
    ) {
        return null
    }
    val schemeSeparator = trimmed.indexOf("://")
    if (schemeSeparator < 0) return null
    val scheme = trimmed.substring(0, schemeSeparator).lowercase()
    if (scheme != "http" && scheme != "https") return null
    val authorityStart = schemeSeparator + 3
    val authorityEnd = trimmed.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        .takeIf { it >= 0 } ?: trimmed.length
    val rawAuthority = trimmed.substring(authorityStart, authorityEnd)
    if (rawAuthority.isEmpty() || '@' in rawAuthority) return null
    val url = trimmed.toHttpUrlOrNull() ?: return null
    if (
        url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null
    ) {
        return null
    }
    return url.toString().trimEnd('/')
}

/** The persistence backend responsible for a tracked read-receipt record. */
enum class ReadReceiptBackend {
    THIRD_PARTY,
    BUILT_IN,
}

data class ReadReceiptRecord(
    val id: String,
    val wxId: String,
    val backend: ReadReceiptBackend,
    val endpoint: String,
    val createdAtMillis: Long,
)

