package dev.ed3c.autowebview.navigation

import kotlinx.coroutines.CancellationException

/**
 * WebView-bound navigation port. Does not expose the raw navigator.
 * Replaced binding (generation mismatch) rejects without side effect.
 * loadUrl success is [NavigationDispatchResult.Accepted], never APPLIED.
 */
class BoundWebViewNavigationPort(
    private val bindingGeneration: Long,
    private val currentBindingGeneration: () -> Long?,
    private val loadUrl: (httpsUrl: String) -> Unit,
) : BrowserNavigationPort {
    var sideEffectCount: Int = 0
        private set

    override suspend fun navigate(command: NavigationCommand): NavigationDispatchResult {
        if (command.bindingGeneration != bindingGeneration) {
            return NavigationDispatchResult.Rejected("binding generation mismatch")
        }
        if (currentBindingGeneration() != bindingGeneration) {
            return NavigationDispatchResult.Rejected("WebView binding replaced")
        }
        val validated = HttpsNavigationUrl.validateAndNormalize(command.httpsUrl)
            .getOrElse { return NavigationDispatchResult.Rejected(it.message ?: "invalid url") }
        if (validated != command.httpsUrl) {
            return NavigationDispatchResult.Rejected("command URL is not normalized")
        }

        return try {
            loadUrl(command.httpsUrl)
            sideEffectCount += 1
            // Returning from loadUrl is not APPLIED — caller must observe main-frame URL.
            NavigationDispatchResult.Accepted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NavigationDispatchResult.Unknown
        }
    }
}
