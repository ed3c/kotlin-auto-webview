package dev.ed3c.autowebview.edge

import dev.ed3c.autowebview.domain.AgentAction

/**
 * Keeps the private-edge payload validator aligned with the repository's
 * canonical AgentAction vocabulary without changing the shared domain model.
 *
 * The stream contract historically called the string map `parameters`, while
 * AgentAction owns it as `arguments`. This read-only alias is intentionally
 * local to the edge package so no second mutable representation is introduced.
 */
internal val AgentAction.parameters: Map<String, String>
    get() = arguments
