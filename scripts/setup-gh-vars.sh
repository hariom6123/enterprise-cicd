#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Set required GitHub repo Variables for the enterprise-cicd pipeline.
# These are referenced as ${{ vars.* }} in .github/workflows/ci-cd-pipeline.yml
# and .github/workflows/pr-validation.yml. They MUST be set in:
#   Repo → Settings → Secrets and variables → Actions → Variables
# before the workflows can run end-to-end.
#
# Usage:
#   1. Authenticate:        gh auth login
#   2. Run this script:     ./scripts/setup-gh-vars.sh
#   3. Verify:              gh variable list
# -----------------------------------------------------------------------------
set -euo pipefail

# Replace placeholder values below with real ones for your environment.
# AWS_ACCOUNT_ID MUST be a 12-digit account id (placeholder is fine for dry-runs,
# pipeline will still fail on AWS steps but YAML/expr validation will pass).
declare -A VARS=(
  [JAVA_VERSION]="17"
  [AWS_REGION]="us-east-1"
  [ECR_REPOSITORY]="spring-boot-app"
  [HELM_CHART_PATH]="./charts/spring-boot-app"
  [AWS_ACCOUNT_ID]="123456789012"
  [DEPLOY_DEV_CLUSTER]="dev-eks"
  [DEPLOY_PROD_CLUSTER]="prod-eks"
)

for name in "${!VARS[@]}"; do
  value="${VARS[$name]}"
  echo "→ setting $name = $value"
  gh variable set "$name" --body "$value"
done

echo
echo "✅ Done. Verify with:  gh variable list"
