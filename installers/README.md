# MechMotor Installers

This directory is reserved for installer-related files and release notes.

## Where to publish Windows installers

The current Windows installer bundle is approximately 284 MB. Do **not** commit that ZIP or large `.exe`/`.msi` binaries directly to normal Git history: GitHub rejects individual regular Git files larger than 100 MB, and binary installers make repository clones unnecessarily large.

Preferred distribution:

1. Build and validate the Windows installer with the **Windows desktop package** GitHub Actions workflow.
2. Publish the approved `.exe` and `.msi` files as assets attached to a versioned GitHub Release.
3. Keep this directory for installer documentation, release manifests, checksums, and small helper scripts. If storing binaries in the repository is an explicit requirement, configure Git LFS first and account for its storage/bandwidth limits.

## Current build

Do not use a hard-coded historical workflow run as the default download source. The Windows installer must be built from the latest `main` commit by the **Windows desktop package** workflow and must complete its packaged-launch, EXE install/launch/uninstall, MSI install/launch/uninstall, database-lifecycle, and checksum steps successfully.

For test builds, use the newest successful workflow artifact whose head SHA matches the current `main` commit. For customer distribution, publish the approved `.exe`, `.msi`, and `SHA256SUMS.txt` as assets on a versioned GitHub Release.

Do not publish an installer as production-ready until the relevant CI/package checks and release acceptance gates pass.