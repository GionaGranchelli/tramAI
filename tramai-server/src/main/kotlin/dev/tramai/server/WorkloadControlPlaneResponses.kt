package dev.tramai.server

import dev.tramai.controlplane.ClassifiedRead
import dev.tramai.controlplane.RegisteredWorkload
import dev.tramai.controlplane.WorkloadStateVersion
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity

/*
 * Transport mapping for the control-plane authority contract (0.7.1e).
 *
 * Extracted from the controller so an endpoint reads as "parse -> command -> map outcome" and the
 * response vocabulary lives in one place. These functions decide HTTP shape only: no authority
 * state is read, compared or mutated here.
 */

/**
 * An authoritative or projection read, with the ETag of the version it reports.
 *
 * The ETag comes from [ClassifiedRead.observedVersion] — the version the read testifies to — rather
 * than from the payload, so the header cannot disagree with the body if the two ever diverge.
 */
internal fun okResponse(read: ClassifiedRead): ResponseEntity<Any> =
    ResponseEntity
        .ok()
        .eTag(workloadEtag(read.observedVersion))
        .body(WorkloadRegistrationResponse.from(read.registration, read.consistency.name))

/** A command outcome that changed nothing or applied exactly once; both are 200 with the ETag. */
internal fun okResponse(registration: RegisteredWorkload): ResponseEntity<Any> =
    ResponseEntity
        .ok()
        .eTag(workloadEtag(registration.stateVersion))
        .body(WorkloadRegistrationResponse.from(registration))

/** A newly created registration is 201 with its ETag. */
internal fun createdResponse(registration: RegisteredWorkload): ResponseEntity<Any> =
    ResponseEntity
        .status(HttpStatus.CREATED)
        .eTag(workloadEtag(registration.stateVersion))
        .body(WorkloadRegistrationResponse.from(registration))

/**
 * Precondition failed (412): the version the command was conditioned on is no longer current.
 *
 * Distinct from a domain conflict (409). The body carries both versions and the response carries the
 * current ETag, so a client can re-read and reconcile without parsing human-readable text.
 */
internal fun preconditionFailed(
    currentVersion: WorkloadStateVersion,
    expectedVersion: WorkloadStateVersion,
): ResponseEntity<Any> {
    val problem =
        ProblemDetail.forStatus(HttpStatus.PRECONDITION_FAILED).apply {
            title = "Precondition failed"
            detail =
                "The command was conditioned on state version ${expectedVersion.value}, " +
                "but the authoritative version is ${currentVersion.value}"
            setProperty("expectedVersion", expectedVersion.value)
            setProperty("currentVersion", currentVersion.value)
        }
    return ResponseEntity
        .status(HttpStatus.PRECONDITION_FAILED)
        .eTag(workloadEtag(currentVersion))
        .body(problem)
}

/**
 * A missing precondition (428) or an unacceptable `If-Match` form (400).
 *
 * Neither is ever a silent write: a mutation without a usable version precondition does not happen.
 */
internal fun preconditionFailureResponse(precondition: WorkloadPrecondition): ResponseEntity<Any> {
    val (status, title, detail) =
        when (precondition) {
            WorkloadPrecondition.Missing -> {
                Triple(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "Precondition required",
                    "This mutation requires an If-Match header carrying the authoritative state version, " +
                        "for example If-Match: \"7\"",
                )
            }

            WorkloadPrecondition.Unsupported -> {
                Triple(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported If-Match precondition",
                    "If-Match must be exactly one strong numeric ETag, for example If-Match: \"7\"; " +
                        "weak tags, lists and * are not version preconditions",
                )
            }

            is WorkloadPrecondition.Expected -> {
                error("an accepted precondition is not a failure")
            }
        }
    return problemResponse(status = status, title = title, detail = detail)
}

/** The deployment scope has no authoritative registration. */
internal fun notFoundResponse(): ResponseEntity<Any> =
    problemResponse(
        status = HttpStatus.NOT_FOUND,
        title = "Workload deployment is not registered",
        detail = "No authoritative registration exists for this deployment scope",
    )

/** RFC 7807 problem response, matching the server's existing error convention. */
internal fun problemResponse(
    status: HttpStatus,
    title: String,
    detail: String,
): ResponseEntity<Any> =
    ResponseEntity
        .status(status)
        .body(
            ProblemDetail.forStatus(status).apply {
                this.title = title
                this.detail = detail
            },
        )
