package dev.ed3c.autowebview.workspace.routing

import dev.ed3c.autowebview.workspace.contract.AuthorityKind
import dev.ed3c.autowebview.workspace.contract.AuthorityRef
import dev.ed3c.autowebview.workspace.contract.DigestRef
import dev.ed3c.autowebview.workspace.contract.EvidenceCeiling
import dev.ed3c.autowebview.workspace.contract.RouteDecision
import dev.ed3c.autowebview.workspace.contract.RouteDecisionState
import dev.ed3c.autowebview.workspace.contract.RouteRequest
import dev.ed3c.autowebview.workspace.contract.SubjectDataClass
import dev.ed3c.autowebview.workspace.contract.SubjectKey
import dev.ed3c.autowebview.workspace.contract.SubjectRef
import kotlinx.serialization.Serializable

@Serializable
enum class FederationRouteClass {
    VERIFY_CLAIM,
    COMPILE_CONTENT,
    EVALUATE_MARKET,
    RESOLVE_METHOD,
    QUALIFY_SKILL,
    RUN_EXPERIMENT,
    RESOLVE_RUNTIME,
    ORCHESTRATE_WORK,
    OPEN_WORK_ITEM,
    PROJECT_HUMAN_VIEW,
}

@Serializable
data class ExactSubjectExpectation(
    val key: SubjectKey,
    val expectedVersion: String? = null,
    val expectedDigest: DigestRef? = null,
) {
    init {
        require(expectedVersion == null || expectedVersion.isNotBlank()) {
            "Expected subject version cannot be blank"
        }
        require(expectedVersion != null || expectedDigest != null) {
            "Every routed subject requires an exact version or digest expectation"
        }
    }
}

@Serializable
data class FederationRouteEnvelope(
    val request: RouteRequest,
    val subjects: Set<ExactSubjectExpectation>,
) {
    init {
        require(subjects.isNotEmpty()) { "Federation route requires exact subject expectations" }
        require(subjects.mapTo(linkedSetOf()) { it.key } == request.exactSubjects) {
            "Route subject expectations must exactly match RouteRequest subjects"
        }
    }
}

@Serializable
data class FederationRouteBinding(
    val capabilityId: String,
    val routeClass: FederationRouteClass,
    val destinationOwner: AuthorityRef,
    val maximumDataClass: SubjectDataClass,
    val maximumEvidenceCeiling: EvidenceCeiling,
) {
    init {
        require(capabilityId.isNotBlank()) { "Federation route capability id cannot be blank" }
        require(capabilityId.length <= 256) { "Federation route capability id is too long" }
    }
}

class FederationRouteCatalog(bindings: Collection<FederationRouteBinding>) {
    private val byCapability: Map<String, FederationRouteBinding>

    init {
        val grouped = bindings.groupBy(FederationRouteBinding::capabilityId)
        require(grouped.none { (_, entries) -> entries.size > 1 }) {
            "Ambiguous federation capability bindings are not admitted"
        }
        byCapability = grouped.mapValues { (_, entries) -> entries.single() }
    }

    fun binding(capabilityId: String): FederationRouteBinding? = byCapability[capabilityId]
}

object StandardFederationRouteCatalog {
    val value = FederationRouteCatalog(
        listOf(
            binding(
                capabilityId = "verify.claim",
                routeClass = FederationRouteClass.VERIFY_CLAIM,
                owner = AuthorityRef(AuthorityKind.DOMAIN_REPOSITORY, "truth-verify-loop"),
            ),
            binding(
                capabilityId = "compile.content.cards",
                routeClass = FederationRouteClass.COMPILE_CONTENT,
                owner = AuthorityRef(AuthorityKind.DOMAIN_REPOSITORY, "ai-content-notes"),
            ),
            binding(
                capabilityId = "compile.requirements.capabilities",
                routeClass = FederationRouteClass.COMPILE_CONTENT,
                owner = AuthorityRef(AuthorityKind.DOMAIN_REPOSITORY, "tech-implementation-atlas"),
            ),
            binding(
                capabilityId = "evaluate.market",
                routeClass = FederationRouteClass.EVALUATE_MARKET,
                owner = AuthorityRef(AuthorityKind.DOMAIN_REPOSITORY, "ai-product-notes"),
            ),
            binding(
                capabilityId = "resolve.method",
                routeClass = FederationRouteClass.RESOLVE_METHOD,
                owner = AuthorityRef(AuthorityKind.METHOD_REPOSITORY, "skills-shared"),
            ),
            binding(
                capabilityId = "qualify.skill",
                routeClass = FederationRouteClass.QUALIFY_SKILL,
                owner = AuthorityRef(AuthorityKind.QUALIFIER, "Skill.md-native"),
            ),
            binding(
                capabilityId = "run.experiment",
                routeClass = FederationRouteClass.RUN_EXPERIMENT,
                owner = AuthorityRef(AuthorityKind.EXPERIMENT_OWNER, "blackbox-auto-research"),
            ),
            binding(
                capabilityId = "resolve.runtime",
                routeClass = FederationRouteClass.RESOLVE_RUNTIME,
                owner = AuthorityRef(AuthorityKind.RUNTIME_OWNER, "runtime-env"),
            ),
            binding(
                capabilityId = "orchestrate.work",
                routeClass = FederationRouteClass.ORCHESTRATE_WORK,
                owner = AuthorityRef(AuthorityKind.ORCHESTRATOR, "bettor-arena"),
            ),
            FederationRouteBinding(
                capabilityId = "open.work-item",
                routeClass = FederationRouteClass.OPEN_WORK_ITEM,
                destinationOwner = AuthorityRef(AuthorityKind.GITHUB, "github-workgraph"),
                maximumDataClass = SubjectDataClass.PUBLIC,
                maximumEvidenceCeiling = EvidenceCeiling.TECHNICAL,
            ),
            FederationRouteBinding(
                capabilityId = "project.human-view",
                routeClass = FederationRouteClass.PROJECT_HUMAN_VIEW,
                destinationOwner = AuthorityRef(AuthorityKind.EXTERNAL, "google-projection"),
                maximumDataClass = SubjectDataClass.PUBLIC,
                maximumEvidenceCeiling = EvidenceCeiling.SOURCE_ONLY,
            ),
        ),
    )

    private fun binding(
        capabilityId: String,
        routeClass: FederationRouteClass,
        owner: AuthorityRef,
    ) = FederationRouteBinding(
        capabilityId = capabilityId,
        routeClass = routeClass,
        destinationOwner = owner,
        maximumDataClass = SubjectDataClass.PUBLIC,
        maximumEvidenceCeiling = EvidenceCeiling.TECHNICAL,
    )
}

fun interface FederationRouteSubjectSource {
    suspend fun resolve(key: SubjectKey): SubjectRef?
}

@Serializable
enum class RouteRequestClaimState {
    NEW,
    IDEMPOTENT_REPLAY,
    CONFLICT,
}

fun interface RouteRequestLedger {
    suspend fun claim(requestId: String, semanticFingerprint: String): RouteRequestClaimState
}

class InMemoryRouteRequestLedger : RouteRequestLedger {
    private val fingerprints = linkedMapOf<String, String>()

    override suspend fun claim(requestId: String, semanticFingerprint: String): RouteRequestClaimState {
        val existing = fingerprints[requestId]
        return when {
            existing == null -> {
                fingerprints[requestId] = semanticFingerprint
                RouteRequestClaimState.NEW
            }
            existing == semanticFingerprint -> RouteRequestClaimState.IDEMPOTENT_REPLAY
            else -> RouteRequestClaimState.CONFLICT
        }
    }
}

@Serializable
data class RouteProposalPacket(
    val requestId: String,
    val caller: AuthorityRef,
    val intent: String,
    val routeClass: FederationRouteClass,
    val destinationOwner: AuthorityRef,
    val evidenceCeiling: EvidenceCeiling,
    val exactSubjects: Set<ExactSubjectExpectation>,
) {
    init {
        require(intent.isNotBlank()) { "Route proposal intent cannot be blank" }
        require(intent.length <= 1_024) { "Route proposal intent is too long" }
        require(exactSubjects.isNotEmpty()) { "Route proposal requires exact subjects" }
    }
}

@Serializable
data class RouteProposalReceipt(
    val requestId: String,
    val routeClass: FederationRouteClass,
    val destinationOwner: AuthorityRef,
    val evidenceCeiling: EvidenceCeiling,
) {
    init {
        require(requestId.isNotBlank()) { "Route proposal receipt request id cannot be blank" }
    }
}

sealed interface RouteProposalResponse {
    data class Acknowledged(val receipt: RouteProposalReceipt) : RouteProposalResponse
    data class Denied(val reasonCode: String) : RouteProposalResponse
    data class TimedOut(val reasonCode: String = "DESTINATION_TIMEOUT") : RouteProposalResponse
}

fun interface RouteProposalSink {
    suspend fun propose(packet: RouteProposalPacket): RouteProposalResponse
}

@Serializable
enum class FederationRouteOutcomeState {
    PROPOSED,
    REJECTED,
    DEFERRED,
}

@Serializable
data class FederationRouteOutcome(
    val state: FederationRouteOutcomeState,
    val decision: RouteDecision,
    val routeClass: FederationRouteClass? = null,
    val idempotentReplay: Boolean = false,
) {
    init {
        require(!decision.executionAuthorityGranted) {
            "Federation routing cannot grant execution authority"
        }
    }
}

class FederationRouter(
    private val catalog: FederationRouteCatalog,
    private val subjectSource: FederationRouteSubjectSource,
    private val requestLedger: RouteRequestLedger,
    private val proposalSink: RouteProposalSink,
) {
    suspend fun route(envelope: FederationRouteEnvelope): FederationRouteOutcome {
        val request = envelope.request
        val binding = catalog.binding(request.requiredCapabilityId)
            ?: return rejected(request, "UNKNOWN_CAPABILITY")

        if (binding.destinationOwner != request.destinationOwner) {
            return rejected(request, "DESTINATION_OWNER_MISMATCH", binding.routeClass)
        }
        if (!evidenceAllows(binding.maximumEvidenceCeiling, request.evidenceCeiling)) {
            return rejected(request, "EVIDENCE_CEILING_EXCEEDS_ROUTE", binding.routeClass)
        }

        val resolved = mutableListOf<SubjectRef>()
        for (expected in envelope.subjects.sortedBy { it.key.logicalId }) {
            val subject = subjectSource.resolve(expected.key)
                ?: return rejected(request, "SUBJECT_NOT_FOUND", binding.routeClass)
            if (subject.key != expected.key) {
                return rejected(request, "SUBJECT_IDENTITY_MISMATCH", binding.routeClass)
            }
            if (expected.expectedVersion != null && subject.version != expected.expectedVersion) {
                return rejected(request, "STALE_SUBJECT_VERSION", binding.routeClass)
            }
            if (expected.expectedDigest != null && subject.digest != expected.expectedDigest) {
                return rejected(request, "STALE_SUBJECT_DIGEST", binding.routeClass)
            }
            if (!dataClassAllows(binding.maximumDataClass, subject.dataClass)) {
                return rejected(request, "DESTINATION_DATA_CLASS_INSUFFICIENT", binding.routeClass)
            }
            resolved += subject
        }

        val fingerprint = semanticFingerprint(envelope, binding)
        val claim = requestLedger.claim(request.requestId, fingerprint)
        if (claim == RouteRequestClaimState.CONFLICT) {
            return rejected(request, "REQUEST_ID_SEMANTIC_CONFLICT", binding.routeClass)
        }

        val packet = RouteProposalPacket(
            requestId = request.requestId,
            caller = request.caller,
            intent = request.intent,
            routeClass = binding.routeClass,
            destinationOwner = binding.destinationOwner,
            evidenceCeiling = request.evidenceCeiling,
            exactSubjects = envelope.subjects,
        )
        return when (val response = proposalSink.propose(packet)) {
            is RouteProposalResponse.Denied -> deferred(
                request = request,
                reasonCode = boundedReason(response.reasonCode, "DESTINATION_DENIED"),
                routeClass = binding.routeClass,
                replay = claim == RouteRequestClaimState.IDEMPOTENT_REPLAY,
            )
            is RouteProposalResponse.TimedOut -> deferred(
                request = request,
                reasonCode = boundedReason(response.reasonCode, "DESTINATION_TIMEOUT"),
                routeClass = binding.routeClass,
                replay = claim == RouteRequestClaimState.IDEMPOTENT_REPLAY,
            )
            is RouteProposalResponse.Acknowledged -> {
                val receipt = response.receipt
                if (
                    receipt.requestId != request.requestId ||
                    receipt.routeClass != binding.routeClass ||
                    receipt.destinationOwner != binding.destinationOwner ||
                    receipt.evidenceCeiling != request.evidenceCeiling
                ) {
                    rejected(request, "DESTINATION_RECEIPT_MISMATCH", binding.routeClass)
                } else {
                    FederationRouteOutcome(
                        state = FederationRouteOutcomeState.PROPOSED,
                        decision = RouteDecision(
                            requestId = request.requestId,
                            state = RouteDecisionState.ADMITTED,
                            destinationOwner = binding.destinationOwner,
                            evidenceCeiling = request.evidenceCeiling,
                            reasonCode = "ROUTE_PROPOSAL_ACKNOWLEDGED",
                            executionAuthorityGranted = false,
                        ),
                        routeClass = binding.routeClass,
                        idempotentReplay = claim == RouteRequestClaimState.IDEMPOTENT_REPLAY,
                    )
                }
            }
        }
    }

    private fun semanticFingerprint(
        envelope: FederationRouteEnvelope,
        binding: FederationRouteBinding,
    ): String = buildString {
        append(envelope.request.requiredCapabilityId)
        append('|')
        append(envelope.request.caller.kind.name)
        append(':')
        append(envelope.request.caller.ownerId)
        append('|')
        append(envelope.request.intent)
        append('|')
        append(binding.routeClass.name)
        append('|')
        append(binding.destinationOwner.kind.name)
        append(':')
        append(binding.destinationOwner.ownerId)
        append('|')
        append(envelope.request.evidenceCeiling.name)
        append('|')
        envelope.subjects
            .sortedBy { it.key.logicalId }
            .forEach { subject ->
                append(subject.key.kind.name)
                append(':')
                append(subject.key.logicalId)
                append('@')
                append(subject.expectedVersion ?: "-")
                append('#')
                append(subject.expectedDigest?.value ?: "-")
                append(';')
            }
    }

    private fun rejected(
        request: RouteRequest,
        reasonCode: String,
        routeClass: FederationRouteClass? = null,
    ): FederationRouteOutcome = FederationRouteOutcome(
        state = FederationRouteOutcomeState.REJECTED,
        decision = RouteDecision(
            requestId = request.requestId,
            state = RouteDecisionState.REJECTED,
            destinationOwner = request.destinationOwner,
            evidenceCeiling = request.evidenceCeiling,
            reasonCode = reasonCode,
            executionAuthorityGranted = false,
        ),
        routeClass = routeClass,
    )

    private fun deferred(
        request: RouteRequest,
        reasonCode: String,
        routeClass: FederationRouteClass,
        replay: Boolean,
    ): FederationRouteOutcome = FederationRouteOutcome(
        state = FederationRouteOutcomeState.DEFERRED,
        decision = RouteDecision(
            requestId = request.requestId,
            state = RouteDecisionState.DEFERRED,
            destinationOwner = request.destinationOwner,
            evidenceCeiling = request.evidenceCeiling,
            reasonCode = reasonCode,
            executionAuthorityGranted = false,
        ),
        routeClass = routeClass,
        idempotentReplay = replay,
    )
}

private fun dataClassAllows(maximum: SubjectDataClass, actual: SubjectDataClass): Boolean =
    dataClassRank(actual) <= dataClassRank(maximum)

private fun dataClassRank(value: SubjectDataClass): Int = when (value) {
    SubjectDataClass.PUBLIC -> 0
    SubjectDataClass.INTERNAL -> 1
    SubjectDataClass.CONFIDENTIAL -> 2
    SubjectDataClass.RESTRICTED -> 3
}

private fun evidenceAllows(maximum: EvidenceCeiling, requested: EvidenceCeiling): Boolean =
    evidenceRank(requested) <= evidenceRank(maximum)

private fun evidenceRank(value: EvidenceCeiling): Int = when (value) {
    EvidenceCeiling.SOURCE_ONLY -> 0
    EvidenceCeiling.TECHNICAL -> 1
    EvidenceCeiling.LIVE_WORKFLOW -> 2
    EvidenceCeiling.USER_VALIDATED -> 3
    EvidenceCeiling.PAID_VALIDATED -> 4
}

private fun boundedReason(value: String, fallback: String): String {
    if (value.isBlank() || value.length > 96) return fallback
    if (!value.all { it.isUpperCase() || it.isDigit() || it == '_' }) return fallback
    return value
}
