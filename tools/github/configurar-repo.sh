#!/usr/bin/env bash
# Deja el repositorio como pide el plan (issue 1): etiquetas, milestones por hito, protección de
# main, entornos y escaneo de secretos. Se puede correr varias veces sin duplicar nada.
#
#   gh auth login                     # con permisos de administración del repositorio
#   tools/github/configurar-repo.sh ORGANIZACION/constructora usuario-responsable-tecnico
set -euo pipefail

REPO=${1:?Uso: configurar-repo.sh <organizacion/repositorio> <usuario-responsable-tecnico>}
RESPONSABLE=${2:?Falta el usuario de GitHub del responsable técnico}
cd "$(dirname "$0")/../.."

echo "== Etiquetas"
while IFS='|' read -r nombre color descripcion; do
  gh label create "$nombre" --repo "$REPO" --color "$color" --description "$descripcion" --force >/dev/null
done <<'ETIQUETAS'
modulo:organizacion|1f3a5f|Empresas, usuarios, roles
modulo:estructura|1f3a5f|Contratos, lotes, prototipos, paquetes
modulo:catalogo|1f3a5f|Materiales, unidades, proveedores
modulo:inventario|1f3a5f|Movimientos, kardex, saldo, costo
modulo:abastecimiento|1f3a5f|Requisición, pedido, recepción
modulo:reportes|1f3a5f|Reportes de solo lectura
modulo:importacion|1f3a5f|Cargas desde Excel
modulo:frontend|2d5486|Pantallas
modulo:backend|2d5486|Backend en general
modulo:infra|2d5486|Servidores, CI/CD, Terraform
tipo:feature|0e8a16|Funcionalidad nueva
tipo:bug|b42318|Algo no funciona
tipo:deuda|fbca04|Deuda técnica
riesgo:alto|b60205|Toca costeo, existencias, permisos o migraciones
severidad:critica|b60205|Piloto: no se registran entradas o salidas, o quedó mal un dato (ADR-015)
severidad:mayor|d93f0b|Piloto: se sigue con un rodeo, o un dato importante se ve mal
severidad:menor|fbca04|Piloto: un detalle
dependencias|ededed|Actualización de dependencias
ia|c5def5|Lo hizo o lo ayudó una IA
ETIQUETAS

echo "== Milestones (uno por hito)"
existentes=$(gh api "repos/$REPO/milestones?state=all&per_page=100" --jq '.[].title')
for hito in "H1 · Plataforma lista" "H2 · Catálogos y estructura" "H3 · Centro de migración" \
            "H4 · Inventario en preproducción" "H5 · Histórico migrado" "H6 · Abastecimiento y reportes" \
            "H7 · Producción y ensayo" "H8 · Corte del piloto" "H9 · Estabilización"; do
  grep -qxF "$hito" <<<"$existentes" || gh api "repos/$REPO/milestones" -f title="$hito" >/dev/null
done

echo "== CODEOWNERS → @$RESPONSABLE"
sed -i.bak "s/@RESPONSABLE-TECNICO/@$RESPONSABLE/g" .github/CODEOWNERS && rm -f .github/CODEOWNERS.bak
echo "   (sube este cambio en el primer PR)"

echo "== Escaneo de secretos, protección al subir y alertas de Dependabot"
gh api -X PATCH "repos/$REPO" --input - >/dev/null <<'JSON'
{
  "delete_branch_on_merge": true,
  "allow_merge_commit": false,
  "allow_rebase_merge": false,
  "allow_squash_merge": true,
  "squash_merge_commit_title": "PR_TITLE",
  "security_and_analysis": {
    "secret_scanning": { "status": "enabled" },
    "secret_scanning_push_protection": { "status": "enabled" }
  }
}
JSON
gh api -X PUT "repos/$REPO/vulnerability-alerts" >/dev/null || true
gh api -X PUT "repos/$REPO/automated-security-fixes" >/dev/null || true

echo "== Protección de main: PR obligatorio, 1 aprobación, CI en verde, sin force-push"
REGLAS=$(gh api "repos/$REPO/rulesets" --jq '.[] | select(.name=="main") | .id' || true)
CUERPO=$(cat <<'JSON'
{
  "name": "main",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    { "type": "pull_request", "parameters": {
        "required_approving_review_count": 1,
        "dismiss_stale_reviews_on_push": true,
        "require_code_owner_review": true,
        "require_last_push_approval": false,
        "required_review_thread_resolution": true } },
    { "type": "required_status_checks", "parameters": {
        "strict_required_status_checks_policy": true,
        "required_status_checks": [
          { "context": "Backend (pruebas contra PostgreSQL 18)" },
          { "context": "Frontend (contrato, lint, pruebas, build)" },
          { "context": "Punta a punta (Playwright contra la app en Docker)" },
          { "context": "Infraestructura y base (lint, Terraform, pruebas SQL)" },
          { "context": "Secretos (gitleaks)" } ] } }
  ]
}
JSON
)
if [[ -n "$REGLAS" ]]; then
  gh api -X PUT "repos/$REPO/rulesets/$REGLAS" --input - <<<"$CUERPO" >/dev/null
else
  gh api -X POST "repos/$REPO/rulesets" --input - <<<"$CUERPO" >/dev/null
fi

echo "== Entornos"
gh api -X PUT "repos/$REPO/environments/preproduccion" >/dev/null
gh api -X PUT "repos/$REPO/environments/produccion" >/dev/null

cat <<FIN

Listo. Falta cargar a mano (Settings › Environments › preproduccion):
  Variables: PREPROD_HOST (nombre del servidor en Tailscale) y PREPROD_URL (https://preprod…)
  Secretos:  PREPROD_SSH_KEY, TS_OAUTH_CLIENT_ID, TS_OAUTH_SECRET
Y en Settings › Secrets › Actions: NVD_API_KEY (para el escaneo semanal de dependencias).
Ver docs/runbooks/primer-arranque.md.
FIN
