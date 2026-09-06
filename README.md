# r10

Public archival repository preserving a historical JVM runtime instrumentation and patch-lifecycle research project.

## Overview

This repository preserves a complete historical project snapshot, including source code, runtime components, build artifacts, diagnostics, integrity data, and supporting tooling.

The engineering work represented here includes:

- JVM runtime instrumentation
- versioned runtime lifecycle management
- state preservation and migration across revisions
- stale-revision cleanup
- integrity-gated loading and verification
- runtime diagnostics and evidence collection
- fail-safe update behavior
- rollback and recovery mechanisms

## Archive status

This repository is published as a **historical engineering archive**.

It is not maintained as a general-purpose end-user product, and no guarantee is made that historical binaries or environment-specific artifacts remain portable or compatible with current environments.

Use the material only in environments and software you are authorized to inspect or modify.

## Repository contents

The archive may contain:

- original source code
- Java runtime/instrumentation components
- native support components
- historical compiled artifacts
- launch and support tooling
- integrity manifests
- diagnostic output
- revision-specific implementation material

Some files are retained specifically because this repository is intended to preserve historical project state rather than represent a minimal source-only distribution.

## Engineering focus

The project primarily explores lifecycle problems that arise when runtime instrumentation evolves across multiple revisions.

Areas of interest include deterministic replacement of stale runtime state, migration of existing state, integrity verification before loading, bounded diagnostics, cleanup of superseded revisions, and recovery when an update cannot be safely applied.

## Releases

GitHub releases represent archival snapshots.

### v1.0.0

The initial GitHub archival snapshot of the project.

The tag is intentionally preserved as the state that was originally archived. Documentation on `main` may receive later clarification without rewriting historical release tags.

## Security and responsible use

This repository contains historical runtime instrumentation research and compiled artifacts.

Do not use the software against systems, processes, accounts, or applications you are not authorized to inspect or modify.

Historical binaries are provided as archival material and should not be treated as trusted production software.

## License

Original source code authored for this project is provided under the MIT License unless otherwise noted.

Third-party software, binaries, libraries, generated material, or externally sourced components are **not automatically relicensed by the MIT License** and remain subject to their original licenses and terms.

See [NOTICE.md](NOTICE.md) for clarification.
