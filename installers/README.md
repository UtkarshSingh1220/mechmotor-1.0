# MechMotor Installers

This directory is reserved for installer-related files and release notes.

## Where to publish Windows installers

The current Windows installer bundle is approximately 284 MB. Do **not** commit that ZIP or large `.exe`/`.msi` binaries directly to normal Git history: GitHub rejects individual regular Git files larger than 100 MB, and binary installers make repository clones unnecessarily large.

Preferred distribution:

1. Build and validate the Windows installer with the **Windows desktop package** GitHub Actions workflow.
2. Publish the approved `.exe` and `.msi` files as assets attached to a versioned GitHub Release.
3. Keep this directory for installer documentation, release manifests, checksums, and small helper scripts. If storing binaries in the repository is an explicit requirement, configure Git LFS first and account for its storage/bandwidth limits.

## Current build

The latest previously successful Windows packaging artifact is available from [GitHub Actions run 39136144558](https://github.com/UtkarshSingh1220/mechmotor-1.0/actions/runs/39136144558). It is a temporary workflow artifact, not a permanent release; check its expiration before relying on it.

Do not publish an installer as production-ready until the relevant CI/package checks and release acceptance gates pass.