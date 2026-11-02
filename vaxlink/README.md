# VaxLink

The OHS Player configuration for the Pre-GDHF VaxLink vignette: a Lao PDR family's baby is born in Thailand, gets two signed vaccination certificates, and is found by a community health worker, verified at a clinic and later seen at a clinic abroad. One app serves three roles: the CHW community role (J2), the clinic facility role (J3) and the foreign clinic viewer role (J4.1). It started from [`workflow-examples`](../workflow-examples), keeping its sign-in, role resolution, per-role sync and questionnaire host. The [`hcert`](./hcert) module hosts the WHO HCERT verification library for reading and verifying certificate QR codes (NE-1).

The work is tracked as the Nawi engineering issues on [nawitech/pre-gdhf-workshop](https://github.com/nawitech/pre-gdhf-workshop/issues).

## Roles

| Role | PractitionerRole code | Journey |
|---|---|---|
| Community health worker | `chw` | J2: registers the newborn, scans the cards, refers |
| Nurse | `nurse` | J3: verifies the cards, imports, runs the schedule |
| Clinician | `clinician` | J4.1: scans a Verifiable Health Link and views the record |

## Certificates (NE-1)

Every patient profile has a **Scan certificate** action. The CHW role reads the WHO vaccination
certificate QR offline and shows it marked *Not verified* (J2). The nurse and clinician roles verify
the signature against the GDHCN DEV trust list first and show the content only when it checked out
(J3); a tampered card shows the failing step and reason and nothing else. On desktop, where there is
no camera, paste the `HC1:` text from the WHO worked example instead. The web build needs https or
localhost for the camera.

The trust list ships with the app (`files/trust/gdhcn-dev-trustlist.json`, fetched 23 Oct 2026) so a
device verifies before its first sync; every **Sync now** also refreshes it from
`tng-cdn-dev.who.int`, and the scan screen shows how old the copy in use is. The engine, the trust
list cache, the DVC to FHIR mapper and the AuditEvent builder live in [`hcert/`](./hcert), which
also explains how to build the library from source. Recording a scan as an Immunization is NE-2
(community) and NE-3 (clinic).

## Setup

1. **JDK 21.** Gradle rejects JDK 25.
2. **The HCERT library**: publish it to your local Maven cache once, as [`hcert/README.md`](./hcert/README.md#building-the-library) describes; it is not on Maven Central yet.
3. **`local.properties`**: copy `local.properties.sample` and set `OAUTH_ISSUER`, `OAUTH_CLIENT_ID` and `FHIR_BASE_URL`.
4. **Gateway**: it must accept `PATCH`, because the engine sends every update that way. The gateway has no resource-type list to edit: its access checker requires a realm role `<VERB>_<TYPE>` (for example `PATCH_GROUP`, `PUT_AUDITEVENT`) for every request and every Bundle entry. `scripts/seed.sh` creates those roles for every type in `files/sync/*.json` and gives them to the demo users; on a realm you manage another way, create the same roles and add them to your practitioners' group. The reference infrastructure realm template does not define roles for Immunization, MedicationStatement, MedicationRequest, AllergyIntolerance, Provenance, ServiceRequest or Procedure.
5. **Demo users**: create the seed service account, fill in `scripts/seed.env` from `scripts/seed.env.sample`, and run `./scripts/seed.sh`.

### Seed service account

`seed.sh` signs in as a Keycloak service account in the app's realm (`ohs-player`).

1. Create an OpenID Connect client, for example `seed-service`.
2. Turn on *Client authentication* and *Service accounts roles*. Leave the other flows off.
3. Copy the client secret from *Credentials*.
4. Under *Service accounts roles*, add `realm-management` → `manage-users` and `manage-realm`.
5. Put `SEED_CLIENT_ID` and `SEED_CLIENT_SECRET` in `scripts/seed.env`. It's git-ignored.

Each run also grants the account `ORG_SCOPE_EXEMPT` and the `GET_*`/`PUT_*` roles the gateway needs for organization and location writes.

### Demo data

The seed creates what three users need to sign in and sync, plus the family the story starts with. Ids are fixed UUIDs in [`scripts/seed-bundle.json`](./scripts/seed-bundle.json), so re-running updates rather than duplicates. Names are realistic but fictional; dates are placeholders.

```
Vientiane Capital Health Department               LA-01
└─ Sikhottabong District Health Office             LA-01-04
   └─ Nongbuathong Health Centre                   LA-01-0412     Outpatient Department
      └─ Ban Nongbuathong Community Health Unit    LA-01-0412-01  Ban Nongbuathong
```

| User | Password | Name | Role | Organization |
|---|---|---|---|---|
| `chw1` | `chw_1234!` | Soukanh Inthavong | `chw` | Ban Nongbuathong Community Health Unit |
| `nurse1` | `nurse_1234!` | Chanthasone Vongsa | `nurse` | Nongbuathong Health Centre |
| `clinician1` | `clinician_1234!` | Somphone Keomany | `clinician` | Nongbuathong Health Centre |

The Souvannalath household is seeded in the community unit's catchment with the father, Khamsing, as head and the mother, Mali. The newborn, Chanthala, is not seeded: the CHW registers her in the demo and scans her two cards. CHW referrals go to Nongbuathong Health Centre, the unit's `partOf`. The server is public, so these passwords are for demos only.

## Run

From this directory:

| Target | Command |
|---|---|
| Desktop | `./gradlew run` |
| Android | `./gradlew assembleDebug` |
| Web (Wasm) | `./gradlew wasmJsBrowserDevelopmentRun` |
| iOS | Open `iosApp/` in Xcode (builds, but the verification engine has no iOS build yet) |

