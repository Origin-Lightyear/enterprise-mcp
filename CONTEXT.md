# Enterprise MCP Planning And Execution

This context describes how a customer Runtime publishes planning knowledge and how an execution plan is bound to the exact knowledge used to create it.

## Language

**Catalog**:
The complete planning knowledge published by one customer Runtime, including only its currently configured capabilities and, when available, its Current MDL.

**Configured Capability**:
A capability whose required local configuration is complete and valid, so the customer Runtime can truthfully advertise it in its Catalog. A Wren query capability is not configured until a valid Current MDL exists; transient runtime health failures do not redefine the capability.
_Avoid_: Discovered capability, installed capability

**Catalog Snapshot**:
An immutable, complete Catalog captured at one point in time and represented by canonical UTF-8 JSON content.
_Avoid_: Live Catalog, mutable Catalog

**Referenced Catalog Snapshot**:
A Catalog Snapshot currently named by a customer Runtime or held by an in-flight Planning Request. Unreferenced snapshots have only a short cleanup grace period; audit history retains their digests rather than their complete content.
_Avoid_: Catalog history, archived Catalog

**Catalog Digest**:
The content identity of a Catalog Snapshot, expressed as `sha256:` followed by 64 lowercase hexadecimal characters. It is distinct from both an artifact digest and an MDL digest.
_Avoid_: Catalog version, artifact digest, MDL digest

**Current MDL**:
The single validated Wren semantic model currently used by a customer Runtime. It has no user-visible release or version history, and its storage is changed only through an MDL Update.
_Avoid_: Active Release, current Release

**MDL Digest**:
The content identity of the Current MDL. It is a fingerprint used for consistency checks, not a release or version number.
_Avoid_: MDL version, Wren Release

**MDL Update**:
An atomic replacement of the Current MDL after the complete candidate has passed validation and the Control Plane has confirmed the matching Catalog Snapshot. If that confirmation is unavailable or differs from the locally calculated Catalog Digest, the Current MDL remains unchanged. A successful update invalidates every unfinished plan bound to the previous MDL Digest.
_Avoid_: Publish Release, activate Release

**Stale Plan**:
An unfinished execution plan whose Catalog Digest or MDL Digest no longer matches the customer Runtime's current state. It must fail closed and be planned again without returning partial results.
_Avoid_: Legacy plan, compatible old plan

**Planning Request**:
A request from one customer Runtime for a plan based on its exact Artifact Digest and current Catalog Digest. The Control Plane must use the named Catalog Snapshot and must not infer a latest Catalog.
_Avoid_: Latest-Catalog request, unbound planning request

**Planning Readiness**:
The state in which the Control Plane has confirmed the customer Runtime's current Catalog Snapshot and can generate plans for it. A Runtime may be alive for administration and recovery while not planning-ready, but it must fail closed on planning execution.
_Avoid_: Process health, Wren health
