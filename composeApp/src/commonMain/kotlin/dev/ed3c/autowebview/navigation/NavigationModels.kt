package dev.ed3c.autowebview.navigation

/**
 * Typed HTTPS navigation command admitted only after policy + HITL confirmation.
 * [bindingGeneration] must match the currently bound WebView port generation.
 */
data class NavigationCommand(
    val proposalId: String,
    val httpsUrl: String,
    val bindingGeneration: Long,
)

sealed interface NavigationDispatchResult {
    /** Side-effect was issued (e.g. loadUrl called). Not APPLIED — observation is separate. */
    data object Accepted : NavigationDispatchResult

    data class Rejected(val reason: String) : NavigationDispatchResult

    /** Crash, timeout, or ambiguous outcome — no auto retry. */
    data object Unknown : NavigationDispatchResult
}

fun interface BrowserNavigationPort {
    suspend fun navigate(command: NavigationCommand): NavigationDispatchResult
}

enum class BrowserActionStatus {
    PROPOSED,
    WAITING_FOR_CONFIRMATION,
    EXECUTING,
    APPLIED,
    NONE,
    UNKNOWN,
    REJECTED,
}

/**
 * Reads the current main-frame URL for postcondition observation.
 * loadUrl return values are never treated as APPLIED.
 */
fun interface MainFrameUrlObserver {
    fun currentMainFrameUrl(): String?
}
