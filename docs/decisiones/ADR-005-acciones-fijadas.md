# ADR-005 · Acciones de GitHub fijadas a un commit

**Contexto.** El 19 de marzo de 2026 un atacante reescribió 76 de 77 etiquetas de
`aquasecurity/trivy-action` para robar secretos de los pipelines que las usaban
(GHSA-69fq-xp46-6x23). El plan técnico usaba `trivy-action@master`.

**Decisión.** Toda acción se referencia por SHA completo con la versión en un comentario. Trivy se
usa con `trivy-action` v0.35.0 (commit `57a97c7e`, el único no alterado) y el binario v0.69.3.
gitleaks corre desde su imagen oficial. Dependabot propone los commits nuevos cada lunes.

**Consecuencias.** Actualizar una acción es un PR visible, no un cambio silencioso en una etiqueta.
