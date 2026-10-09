package cz.peelco.jolt.domain.model

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * Which Jolt Server the app talks to. Self-hostable, so the base URL is a
 * setting; accounts don't transfer between servers, so changing it signs out.
 */
@Serializable
data class ServerConfiguration(
    /** Full API base including `/api/v1`, stored verbatim, no trailing slash. */
    val baseUrl: String,
) {
    val host: String? get() = runCatching { URI(baseUrl).host }.getOrNull()

    enum class ValidationError(
        val message: String,
    ) {
        EMPTY("Enter a server URL."),
        MALFORMED("That doesn't look like a URL."),
        INSECURE_SCHEME("The URL must start with https:// — Android blocks plain HTTP."),
    }

    sealed interface ParseResult {
        data class Valid(
            val configuration: ServerConfiguration,
        ) : ParseResult

        data class Invalid(
            val error: ValidationError,
        ) : ParseResult
    }

    companion object {
        /** An obvious placeholder the user replaces under Settings → Server. */
        val PLACEHOLDER = ServerConfiguration("https://jolt.example.com/api/v1")

        /** The build's default, falling back to the placeholder when unusable. */
        fun bundledDefault(value: String?): ServerConfiguration =
            (value?.let(::parse) as? ParseResult.Valid)?.configuration ?: PLACEHOLDER

        /**
         * Parses user input. Strict about the scheme: cleartext is blocked by
         * the platform, and failing here says why instead of failing later.
         */
        fun parse(input: String): ParseResult {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return ParseResult.Invalid(ValidationError.EMPTY)
            // A trailing slash would make `//friends` when paths are appended.
            val normalized = trimmed.removeSuffix("/")
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return ParseResult.Invalid(ValidationError.MALFORMED)
            val scheme = uri.scheme?.lowercase() ?: return ParseResult.Invalid(ValidationError.MALFORMED)
            if (uri.host.isNullOrEmpty()) return ParseResult.Invalid(ValidationError.MALFORMED)
            if (scheme != "https") return ParseResult.Invalid(ValidationError.INSECURE_SCHEME)
            return ParseResult.Valid(ServerConfiguration(normalized))
        }
    }
}
