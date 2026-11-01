# Build verification

## Current identity

| Item | Value |
| --- | --- |
| Version | `0.4.0` |
| Version code | `13` |
| Application ID | `com.personal.healthbridge.reinstall` |
| Update baseline | Public `0.3.6`, version code `12` |
| Certificate SHA-256 | `98ec08ec0640003688d3c99a608cba45c75c0caea33e63d286fc640e4f454292` |

Public releases from collision-free baseline `0.3.4` use this package and fixed signing identity. The current update preserves that identity and the encrypted settings/cursor format. Certificate and package compatibility establish the update lineage; they do not substitute for an observed device installation test.

## Local verification

The source commit or exact source tree, environment and unsigned APK checksum identify the tested candidate. Run the relevant unit tests and release compilation locally, then inspect the unsigned APK's package, version, archive integrity and required manifest components. The `0.4.0` checks cover battery choices/migration, scheduled deadline calculations, alarm/work fallback behavior and the retained sync data path. Record actual commands and outcomes in the task's verification evidence; this document does not declare unexecuted tests passed.

Health Connect account permissions, Google OAuth, background execution over several days, Samsung sleep-app behavior and installation on the user's device are separate runtime observations. Mark any missing runtime evidence `UNVERIFIED` while preserving the valid compiler, unit-test and artifact evidence.

## Remote signing of the same APK

`.github/workflows/sign-verified-apk.yml` accepts a source SHA, draft release tag, unsigned asset name and SHA-256. It requires all of the following before publication:

1. Dispatch from `main`, with `github.sha` equal to the supplied exact source SHA.
2. Existing `v0.4.0` tag resolving to that SHA and an unpublished draft Release.
3. Downloaded `HealthBridge-v0.4.0-unsigned.apk` matching the supplied SHA-256.
4. Unsigned APK archive, application ID, version code and version name matching the current identity.
5. Persistent signing key restored only inside the runner from `HEALTHBRIDGE_PUBLIC_SIGNING_BUNDLE_BASE64` and certificate matched to the fixed SHA-256.
6. 16 KB ZIP alignment, signing and signature verification of the existing local-build APK, without another build or emulator run.
7. Final `HealthBridge-v0.4.0.apk` and `HealthBridge-v0.4.0.sha256` uploaded to the draft, downloaded again and checked by checksum and byte comparison.
8. Unsigned input asset removed before the draft becomes public.

The Release also receives `signing-certificate.txt`, `zipalign-verification.txt` and `release-verification.json`. The JSON binds source SHA, unsigned and signed checksums, package/version/certificate and exact workflow run/attempt. Signing evidence complements local test/build evidence; a successful workflow alone is not a claim of physical-device runtime success.

The legacy build-and-sign workflow remains a separately controlled recovery path. Historical `.github/*report.json` files describe their recorded historical run and must not be mistaken for current `0.4.0` validation.

No health statistics, account identifiers, spreadsheet IDs, tokens or private signing material belong in verification reports.
