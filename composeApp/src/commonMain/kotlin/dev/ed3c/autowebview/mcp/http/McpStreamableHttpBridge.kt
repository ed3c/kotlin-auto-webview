package dev.ed3c.autowebview.mcp.http

import dev.ed3c.autowebview.mcp.BrowserMcpGateway
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Portable admission and JSON-RPC forwarding layer for MCP Streamable HTTP POST requests.
 *
 * It owns no socket, server engine, TLS key, bearer token, mobile listener, or browser/native
 * execution authority. A concrete host mounts it behind an admitted private or loopback route.
 */
class McpStreamableHttpBridge(
    private val gateway: McpJsonRpcGateway,
    private val endpointPolicy: McpHttpEndpointPolicy,
    private val authenticationVerifier: McpHttpAuthenticationVerifier,
    private val replayGuard: McpHttpReplayGuard = BoundedMcpHttpReplayGuard(),
    private val observer: McpHttpBridgeObserver = McpHttpBridgeObserver { },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun handle(request: McpHttpBridgeRequest, nowEpochMs: Long): McpHttpBridgeResponse {
        var rpcMethod: String? = null
        var rpcId: JsonElement = JsonNull
        var gatewayInvoked = false

        try {
            validateTransport(request)
            val authentication = authenticate(request)
            val rpc = parseJsonRpc(request.body)
            rpcMethod = rpc.method
            rpcId = rpc.id
            validateRequestMetadataHeaders(request, rpc)
            validateRpcSurface(rpc)
            protectAgainstReplay(rpc, authentication, nowEpochMs)

            if (rpc.isNotification) {
                val response = acceptedNotificationResponse()
                observe(
                    receipt(
                        outcome = McpHttpBridgeOutcome.NOTIFICATION_ACCEPTED,
                        rpcMethod = rpc.method,
                        response = response,
                        gatewayInvoked = false,
                        sideEffectState = McpHttpSideEffectState.NOT_APPLICABLE,
                    ),
                )
                return response
            }

            gatewayInvoked = true
            val gatewayBody = gateway.handle(request.body)
            val gatewayEvidence = validateGatewayResponse(gatewayBody, rpc.id)
            val response = McpHttpBridgeResponse(
                status = 200,
                headers = responseHeaders(
                    contentType = MEDIA_TYPE_JSON,
                    protocolVersion = optionalSingleHeader(request, HEADER_PROTOCOL_VERSION),
                ),
                body = gatewayBody,
            )
            val sideEffectState = when {
                gatewayEvidence.isError -> McpHttpSideEffectState.NOT_STARTED
                rpc.method == METHOD_TOOLS_CALL && rpc.name == TOOL_PROPOSE_NAVIGATION ->
                    McpHttpSideEffectState.PROPOSAL_ONLY
                else -> McpHttpSideEffectState.NOT_APPLICABLE
            }
            observe(
                receipt(
                    outcome = McpHttpBridgeOutcome.RESPONSE_RETURNED,
                    rpcMethod = rpc.method,
                    response = response,
                    gatewayInvoked = true,
                    sideEffectState = sideEffectState,
                ),
            )
            return response
        } catch (cancelled: CancellationException) {
            observe(
                McpHttpBridgeReceipt(
                    outcome = McpHttpBridgeOutcome.CANCELLED_OR_TIMED_OUT,
                    rpcMethod = rpcMethod,
                    httpStatus = null,
                    errorCode = null,
                    gatewayInvoked = gatewayInvoked,
                    sideEffectState = if (gatewayInvoked) {
                        McpHttpSideEffectState.UNKNOWN
                    } else {
                        McpHttpSideEffectState.NOT_STARTED
                    },
                ),
            )
            throw cancelled
        } catch (failure: McpHttpAdmissionFailure) {
            val response = errorResponse(failure.code, rpcId)
            val outcome = if (
                failure.code == McpHttpBridgeErrorCode.GATEWAY_RESPONSE_INVALID ||
                failure.code == McpHttpBridgeErrorCode.GATEWAY_FAILURE
            ) {
                McpHttpBridgeOutcome.GATEWAY_FAILURE
            } else {
                McpHttpBridgeOutcome.REJECTED
            }
            observe(
                receipt(
                    outcome = outcome,
                    rpcMethod = rpcMethod,
                    response = response,
                    gatewayInvoked = gatewayInvoked,
                    sideEffectState = if (gatewayInvoked) {
                        McpHttpSideEffectState.UNKNOWN
                    } else {
                        McpHttpSideEffectState.NOT_STARTED
                    },
                ),
            )
            return response
        } catch (_: Exception) {
            val code = if (gatewayInvoked) {
                McpHttpBridgeErrorCode.GATEWAY_FAILURE
            } else {
                McpHttpBridgeErrorCode.INTERNAL_FAILURE
            }
            val response = errorResponse(code, rpcId)
            observe(
                receipt(
                    outcome = if (gatewayInvoked) {
                        McpHttpBridgeOutcome.GATEWAY_FAILURE
                    } else {
                        McpHttpBridgeOutcome.REJECTED
                    },
                    rpcMethod = rpcMethod,
                    response = response,
                    gatewayInvoked = gatewayInvoked,
                    sideEffectState = if (gatewayInvoked) {
                        McpHttpSideEffectState.UNKNOWN
                    } else {
                        McpHttpSideEffectState.NOT_STARTED
                    },
                ),
            )
            return response
        }
    }

    private fun validateTransport(request: McpHttpBridgeRequest) {
        if (!request.method.equals("POST", ignoreCase = true)) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.METHOD_NOT_ALLOWED)
        }
        val requestScheme = runCatching { normalizeMcpHttpScheme(request.scheme) }.getOrNull()
        val requestAuthority = runCatching { normalizeMcpHttpAuthority(request.authority) }.getOrNull()
        if (
            requestScheme != endpointPolicy.normalizedScheme ||
            requestAuthority != endpointPolicy.normalizedAuthority ||
            request.path != endpointPolicy.path
        ) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.ENDPOINT_MISMATCH)
        }
        if (!request.query.isNullOrEmpty()) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.QUERY_FORBIDDEN)
        }

        val declaredLength = request.declaredContentLength
        if (declaredLength != null && declaredLength < 0) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.BODY_LENGTH_INVALID)
        }
        if (declaredLength != null && declaredLength > endpointPolicy.maxRequestBodyBytes) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.BODY_TOO_LARGE)
        }
        val actualBodyBytes = request.body.encodeToByteArray().size
        if (actualBodyBytes > endpointPolicy.maxRequestBodyBytes) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.BODY_TOO_LARGE)
        }
        if (declaredLength != null && declaredLength != actualBodyBytes.toLong()) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.BODY_LENGTH_INVALID)
        }

        val contentType = optionalSingleHeader(request, HEADER_CONTENT_TYPE)
            ?: throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.CONTENT_TYPE_REQUIRED)
        if (mediaType(contentType) != MEDIA_TYPE_JSON) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.CONTENT_TYPE_REQUIRED)
        }

        val acceptedMediaTypes = repeatedHeader(request, HEADER_ACCEPT)
            .flatMap(::splitMediaTypes)
            .toSet()
        if (MEDIA_TYPE_JSON !in acceptedMediaTypes || MEDIA_TYPE_EVENT_STREAM !in acceptedMediaTypes) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.ACCEPT_REQUIRED)
        }

        if (optionalSingleHeader(request, HEADER_SESSION_ID) != null) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.SESSION_MODE_UNSUPPORTED)
        }

        val origin = optionalSingleHeader(request, HEADER_ORIGIN)
        if (origin == null) {
            if (!endpointPolicy.allowMissingOrigin) {
                throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.ORIGIN_REJECTED)
            }
        } else {
            val normalizedOrigin = runCatching { normalizeMcpHttpOrigin(origin) }.getOrNull()
            if (normalizedOrigin !in endpointPolicy.normalizedOrigins) {
                throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.ORIGIN_REJECTED)
            }
        }
    }

    private suspend fun authenticate(
        request: McpHttpBridgeRequest,
    ): McpHttpAuthenticationDecision.Accepted {
        val authorization = optionalSingleHeader(request, HEADER_AUTHORIZATION)
        val authentication = try {
            authenticationVerifier.verify(
                McpHttpAuthenticationInput(
                    authorizationHeader = authorization,
                    scheme = endpointPolicy.normalizedScheme,
                    authority = endpointPolicy.normalizedAuthority,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.AUTHENTICATION_UNAVAILABLE)
        }
        return when (authentication) {
            is McpHttpAuthenticationDecision.Accepted -> authentication
            is McpHttpAuthenticationDecision.Rejected -> {
                val code = when (authentication.reason) {
                    McpHttpAuthenticationRejectionReason.MISSING_CREDENTIALS ->
                        McpHttpBridgeErrorCode.AUTHENTICATION_REQUIRED
                    else -> McpHttpBridgeErrorCode.AUTHENTICATION_REJECTED
                }
                throw McpHttpAdmissionFailure(code)
            }
        }
    }

    private fun parseJsonRpc(body: String): ParsedJsonRpc {
        val element = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.MALFORMED_JSON)
        }
        val request = element as? JsonObject
            ?: throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        if (request["jsonrpc"].stringValue() != JSON_RPC_VERSION) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        }
        if ("result" in request || "error" in request) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        }
        val method = request["method"].stringValue()
            ?: throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        if (method.isBlank() || method.any(Char::isISOControl)) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        }
        val params = when (val raw = request["params"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> raw
            else -> throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        }
        val idPresent = "id" in request
        val id = request["id"] ?: JsonNull
        if (idPresent && !validJsonRpcId(id)) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        }
        return ParsedJsonRpc(
            method = method,
            params = params,
            id = id,
            isNotification = !idPresent,
            name = params["name"].stringValue(),
            arguments = params["arguments"] as? JsonObject,
        )
    }

    private fun validateRequestMetadataHeaders(request: McpHttpBridgeRequest, rpc: ParsedJsonRpc) {
        val protocolVersion = optionalSingleHeader(request, HEADER_PROTOCOL_VERSION)
        if (protocolVersion != null && protocolVersion !in SUPPORTED_PROTOCOL_VERSIONS) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.PROTOCOL_VERSION_UNSUPPORTED)
        }

        val bodyProtocolVersion = when (rpc.method) {
            METHOD_INITIALIZE -> rpc.params["protocolVersion"].stringValue()
            else -> {
                val metadata = rpc.params["_meta"] as? JsonObject
                metadata?.get(META_PROTOCOL_VERSION)?.stringValue()
            }
        }
        if (rpc.method == METHOD_INITIALIZE && bodyProtocolVersion !in SUPPORTED_LEGACY_PROTOCOL_VERSIONS) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.PROTOCOL_VERSION_UNSUPPORTED)
        }
        if (protocolVersion != null && bodyProtocolVersion != null && protocolVersion != bodyProtocolVersion) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.REQUEST_HEADER_MISMATCH)
        }

        val mirroredMethod = optionalSingleHeader(request, HEADER_METHOD)
        if (mirroredMethod != null && mirroredMethod != rpc.method) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.REQUEST_HEADER_MISMATCH)
        }
        val mirroredName = optionalSingleHeader(request, HEADER_NAME)
        if (mirroredName != null && mirroredName != rpc.name) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.REQUEST_HEADER_MISMATCH)
        }
    }

    private fun validateRpcSurface(rpc: ParsedJsonRpc) {
        if (rpc.isNotification) {
            if (rpc.method != NOTIFICATION_INITIALIZED) {
                throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.RPC_METHOD_NOT_ADMITTED)
            }
            return
        }
        if (rpc.method !in ALLOWED_REQUEST_METHODS) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.RPC_METHOD_NOT_ADMITTED)
        }
        if (rpc.method == NOTIFICATION_INITIALIZED) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        }
        if (rpc.method == METHOD_TOOLS_CALL && rpc.name !in ALLOWED_TOOL_NAMES) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.TOOL_NOT_ADMITTED)
        }
        if (rpc.method == METHOD_TOOLS_CALL && rpc.name == TOOL_PROPOSE_NAVIGATION) {
            validateNavigationArguments(rpc.arguments)
        }
    }

    private fun validateNavigationArguments(arguments: JsonObject?) {
        val admitted = arguments
            ?: throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        if (admitted.keys != setOf(ARGUMENT_URL)) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        }
        val url = admitted[ARGUMENT_URL] as? JsonPrimitive
            ?: throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        if (
            !url.isString ||
            url.content.length > MAX_URL_CHARACTERS ||
            !url.content.startsWith(HTTPS_PREFIX) ||
            url.content.any { it.code < 0x20 || it.code == 0x7f }
        ) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        }
    }

    private suspend fun protectAgainstReplay(
        rpc: ParsedJsonRpc,
        authentication: McpHttpAuthenticationDecision.Accepted,
        nowEpochMs: Long,
    ) {
        if (rpc.method != METHOD_TOOLS_CALL || rpc.name != TOOL_PROPOSE_NAVIGATION) return
        val arguments = rpc.arguments
            ?: throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.INVALID_JSON_RPC)
        val replayKey = semanticActionReplayKey(
            subjectId = authentication.subjectId,
            credentialEpoch = authentication.credentialEpoch,
            scheme = endpointPolicy.normalizedScheme,
            authority = endpointPolicy.normalizedAuthority,
            path = endpointPolicy.path,
            method = rpc.method,
            toolName = rpc.name,
            arguments = arguments,
        )
        val replayDecision = try {
            replayGuard.admit(replayKey, nowEpochMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.REPLAY_GUARD_UNAVAILABLE)
        }
        when (replayDecision) {
            McpHttpReplayDecision.ACCEPTED -> Unit
            McpHttpReplayDecision.DUPLICATE ->
                throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.REPLAY_DETECTED)
            McpHttpReplayDecision.CAPACITY_EXHAUSTED ->
                throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.REPLAY_CAPACITY_EXHAUSTED)
        }
    }

    private fun validateGatewayResponse(body: String, expectedId: JsonElement): GatewayResponseEvidence {
        if (body.encodeToByteArray().size > endpointPolicy.maxResponseBodyBytes) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.GATEWAY_RESPONSE_INVALID)
        }
        val response = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.GATEWAY_RESPONSE_INVALID)
        if (response["jsonrpc"].stringValue() != JSON_RPC_VERSION) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.GATEWAY_RESPONSE_INVALID)
        }
        if (response["id"] != expectedId) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.GATEWAY_RESPONSE_INVALID)
        }
        val hasResult = "result" in response
        val hasError = "error" in response
        if (hasResult == hasError) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.GATEWAY_RESPONSE_INVALID)
        }
        return GatewayResponseEvidence(isError = hasError)
    }

    private fun repeatedHeader(request: McpHttpBridgeRequest, name: String): List<String> =
        request.headers.entries
            .filter { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
            .flatMap { (_, values) -> values }
            .onEach { value ->
                if (value.any(Char::isISOControl)) {
                    throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.HEADER_INVALID)
                }
            }

    private fun optionalSingleHeader(request: McpHttpBridgeRequest, name: String): String? {
        val values = repeatedHeader(request, name)
        if (values.size > 1) {
            throw McpHttpAdmissionFailure(McpHttpBridgeErrorCode.DUPLICATE_HEADER)
        }
        return values.singleOrNull()?.trim()
    }

    private fun acceptedNotificationResponse(): McpHttpBridgeResponse = McpHttpBridgeResponse(
        status = 202,
        headers = responseHeaders(contentType = null, protocolVersion = null),
        body = null,
    )

    private fun errorResponse(code: McpHttpBridgeErrorCode, id: JsonElement): McpHttpBridgeResponse {
        val headers = responseHeaders(contentType = MEDIA_TYPE_JSON, protocolVersion = null).toMutableMap()
        if (code == McpHttpBridgeErrorCode.METHOD_NOT_ALLOWED) headers[HEADER_ALLOW] = "POST"
        val body = buildJsonObject {
            put("jsonrpc", JSON_RPC_VERSION)
            put("id", id)
            putJsonObject("error") {
                put("code", code.jsonRpcCode)
                put("message", code.safeMessage)
            }
        }.toString()
        return McpHttpBridgeResponse(
            status = code.status,
            headers = headers,
            body = body,
            errorCode = code,
        )
    }

    private fun responseHeaders(contentType: String?, protocolVersion: String?): Map<String, String> =
        buildMap {
            put("Cache-Control", "no-store")
            put("X-Content-Type-Options", "nosniff")
            if (contentType != null) put("Content-Type", contentType)
            if (protocolVersion != null) put("MCP-Protocol-Version", protocolVersion)
        }

    private fun receipt(
        outcome: McpHttpBridgeOutcome,
        rpcMethod: String?,
        response: McpHttpBridgeResponse,
        gatewayInvoked: Boolean,
        sideEffectState: McpHttpSideEffectState,
    ): McpHttpBridgeReceipt = McpHttpBridgeReceipt(
        outcome = outcome,
        rpcMethod = rpcMethod,
        httpStatus = response.status,
        errorCode = response.errorCode,
        gatewayInvoked = gatewayInvoked,
        sideEffectState = sideEffectState,
    )

    private fun observe(receipt: McpHttpBridgeReceipt) {
        try {
            observer.record(receipt)
        } catch (_: Exception) {
            // Observability must not gain request authority or alter the wire result.
        }
    }

    private data class ParsedJsonRpc(
        val method: String,
        val params: JsonObject,
        val id: JsonElement,
        val isNotification: Boolean,
        val name: String?,
        val arguments: JsonObject?,
    )

    private data class GatewayResponseEvidence(val isError: Boolean)

    private companion object {
        const val JSON_RPC_VERSION = "2.0"
        const val MEDIA_TYPE_JSON = "application/json"
        const val MEDIA_TYPE_EVENT_STREAM = "text/event-stream"
        const val HEADER_ACCEPT = "Accept"
        const val HEADER_ALLOW = "Allow"
        const val HEADER_AUTHORIZATION = "Authorization"
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEADER_METHOD = "Mcp-Method"
        const val HEADER_NAME = "Mcp-Name"
        const val HEADER_ORIGIN = "Origin"
        const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
        const val HEADER_SESSION_ID = "Mcp-Session-Id"
        const val METHOD_INITIALIZE = "initialize"
        const val METHOD_PING = "ping"
        const val METHOD_SERVER_DISCOVER = "server/discover"
        const val METHOD_TOOLS_CALL = "tools/call"
        const val METHOD_TOOLS_LIST = "tools/list"
        const val NOTIFICATION_INITIALIZED = "notifications/initialized"
        const val TOOL_CAPTURE_CONTEXT = "browser_capture_context"
        const val TOOL_PROPOSE_NAVIGATION = "browser_propose_navigation"
        const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
        const val ARGUMENT_URL = "url"
        const val HTTPS_PREFIX = "https://"
        const val MAX_URL_CHARACTERS = 2_048

        val SUPPORTED_LEGACY_PROTOCOL_VERSIONS = setOf(BrowserMcpGateway.LEGACY_PROTOCOL_VERSION)
        val SUPPORTED_PROTOCOL_VERSIONS = setOf(
            BrowserMcpGateway.LEGACY_PROTOCOL_VERSION,
            BrowserMcpGateway.MODERN_PROTOCOL_VERSION,
        )
        val ALLOWED_REQUEST_METHODS = setOf(
            METHOD_SERVER_DISCOVER,
            METHOD_INITIALIZE,
            METHOD_PING,
            METHOD_TOOLS_LIST,
            METHOD_TOOLS_CALL,
        )
        val ALLOWED_TOOL_NAMES = setOf(TOOL_CAPTURE_CONTEXT, TOOL_PROPOSE_NAVIGATION)
    }
}

private class McpHttpAdmissionFailure(val code: McpHttpBridgeErrorCode) : Exception()

private fun mediaType(value: String): String = value.substringBefore(';').trim().lowercase()

private fun splitMediaTypes(value: String): List<String> =
    value.split(',').map(::mediaType).filter(String::isNotEmpty)

private fun JsonElement?.stringValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun validJsonRpcId(id: JsonElement): Boolean = when (id) {
    JsonNull -> true
    is JsonPrimitive -> id.isString || id.content.toDoubleOrNull() != null
    else -> false
}
