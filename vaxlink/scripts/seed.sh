#!/usr/bin/env bash
# Creates the demo users and the FHIR records they need to sign in and sync. Safe to re-run.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
if [[ -f "$here/seed.env" ]]; then
  # shellcheck source=/dev/null
  source "$here/seed.env"
fi
: "${KEYCLOAK_URL:?}" "${KEYCLOAK_REALM:?}" "${SEED_CLIENT_ID:?}" "${SEED_CLIENT_SECRET:?}"
: "${FHIR_URL:?}"

types=(Patient Encounter Observation QuestionnaireResponse ServiceRequest MedicationRequest
  Task Procedure Practitioner PractitionerRole Location Organization Questionnaire Patient Group RelatedPerson)
verbs=(GET POST PUT PATCH DELETE)
api="$KEYCLOAK_URL/admin/realms/$KEYCLOAK_REALM"
fhir_base="${FHIR_URL%/}"

new_token() {
  curl -fsS \
    -d grant_type=client_credentials -d client_id="$SEED_CLIENT_ID" -d client_secret="$SEED_CLIENT_SECRET" \
    "$KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token" | jq -r .access_token
}
token=$(new_token)

admin() { curl -fsS -H "Authorization: Bearer $token" -H 'Content-Type: application/json' "$@"; }
fhir() { curl -fsS -H "Authorization: Bearer $token" -H 'Content-Type: application/fhir+json' "$@"; }

for type in "${types[@]}"; do
  for verb in "${verbs[@]}"; do
    role="${verb}_${type^^}"
    admin -o /dev/null "$api/roles/$role" 2>/dev/null || admin -X POST "$api/roles" -d "{\"name\":\"$role\"}"
  done
done
admin -o /dev/null "$api/roles/ORG_SCOPE_EXEMPT" 2>/dev/null ||
  admin -X POST "$api/roles" -d '{"name":"ORG_SCOPE_EXEMPT"}'
roles=$(admin "$api/roles?max=1000" | jq '[.[] | select(.name | test("^(GET|POST|PUT|PATCH|DELETE)_"))]')

# The gateway checks this script's own token, and only an exempt caller may write Organization
# and Location.
service_account=$(admin "$api/users?username=service-account-${SEED_CLIENT_ID,,}&exact=true" | jq -r '.[0].id')
admin -X POST "$api/users/$service_account/role-mappings/realm" \
  -d "$(admin "$api/roles?max=1000" | jq '[.[] | select(.name | test("^(GET|PUT)_|^ORG_SCOPE_EXEMPT$"))]')"

ensure_user() {
  local username=$1 password=$2 first=$3 last=$4 id
  id=$(admin "$api/users?username=$username&exact=true" | jq -r '.[0].id // empty')
  if [[ -z $id ]]; then
    admin -X POST "$api/users" -d "$(jq -n --arg u "$username" --arg f "$first" --arg l "$last" \
      '{username: $u, enabled: true, firstName: $f, lastName: $l, email: "\($u)@demo.ohs.dev", emailVerified: true}')"
    id=$(admin "$api/users?username=$username&exact=true" | jq -r '.[0].id')
  fi
  admin -X PUT "$api/users/$id/reset-password" \
    -d "$(jq -n --arg p "$password" '{type: "password", value: $p, temporary: false}')"
  admin -X POST "$api/users/$id/role-mappings/realm" -d "$roles"
  echo "$id"
}

chw=$(ensure_user chw1 'chw_1234!' Soukanh Inthavong)
nurse=$(ensure_user nurse1 'nurse_1234!' Chanthasone Vongsa)
clinician=$(ensure_user clinician1 'clinician_1234!' Somphone Keomany)
token=$(new_token)

sed -e "s/__CHW_SUB__/$chw/" -e "s/__NURSE_SUB__/$nurse/" -e "s/__CLINICIAN_SUB__/$clinician/" \
  "$here/seed-bundle.json" |
  fhir -X POST "$fhir_base/" --data-binary @- |
  jq -r '.entry[].response.status' | sort | uniq -c

printf '%-12s %-10s %-28s %s\n' practitioner role organization location
for id in $(jq -r '.entry[].resource | select(.resourceType == "PractitionerRole") | .id' "$here/seed-bundle.json"); do
  fhir "$fhir_base/PractitionerRole/$id" |
    jq -r '[.practitioner.reference, .code[0].coding[0].code, .organization.reference, .location[0].reference] | @tsv'
done
