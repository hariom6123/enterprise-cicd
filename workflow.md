# GitHub Actions CI/CD Workflow — Spring Boot on AWS EKS

**Triggered workflow files** (deploy these in your repo):
- `.github/workflows/ci-cd-pipeline.yml` — main push/PR pipeline
- `.github/workflows/pr-validation.yml` — PR validation

**Supporting files** (called by the workflows above):
- `.github/workflows/_reusable-build.yml`
- `.github/workflows/_reusable-unit-tests.yml`
- `.github/workflows/_reusable-integration-tests.yml`
- `.github/workflows/_reusable-sonarqube.yml`
- `.github/workflows/_reusable-dependency-scan.yml`
- `.github/workflows/_reusable-push-ecr.yml`
- `.github/workflows/_reusable-deploy.yml`
- `.github/workflows/_reusable-rollback.yml`
- `.github/actions/slack-notify/action.yml`

The **calling workflows** are thin — each job is a single `uses:` line that delegates to a reusable workflow. The reusable workflows contain all the actual step-by-step logic. This makes each piece testable, shareable across repos, and easy to evolve independently.

---

## Triggered Workflows

### `.github/workflows/ci-cd-pipeline.yml` (thin caller)

```yaml
name: Enterprise CI/CD Pipeline

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main, develop]

env:
  JAVA_VERSION: '17'
  MAVEN_OPTS: '-Xmx2g -XX:MaxMetaspaceSize=512m'
  AWS_REGION: 'us-east-1'
  ECR_REPOSITORY: 'spring-boot-app'
  IMAGE_TAG: ${{ github.sha }}
  HELM_CHART_PATH: './charts/spring-boot-app'

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}

permissions:
  contents: read
  id-token: write
  pull-requests: write
  security-events: write

jobs:
  build:
    uses: ./.github/workflows/_reusable-build.yml
    with:
      java-version: ${{ env.JAVA_VERSION }}
      ecr-registry: ${{ vars.AWS_ACCOUNT_ID }}.dkr.ecr.${{ env.AWS_REGION }}.amazonaws.com
      ecr-repository: ${{ env.ECR_REPOSITORY }}
      image-tag: ${{ env.IMAGE_TAG }}
      aws-region: ${{ env.AWS_REGION }}
      aws-account-id: ${{ vars.AWS_ACCOUNT_ID }}

  unit-tests:
    needs: build
    uses: ./.github/workflows/_reusable-unit-tests.yml
    with: { java-version: ${{ env.JAVA_VERSION }} }

  integration-tests:
    needs: build
    uses: ./.github/workflows/_reusable-integration-tests.yml
    with: { java-version: ${{ env.JAVA_VERSION }} }

  sonarqube-scan:
    needs: build
    uses: ./.github/workflows/_reusable-sonarqube.yml
    with: { java-version: ${{ env.JAVA_VERSION }} }
    secrets: inherit

  dependency-scan:
    needs: build
    uses: ./.github/workflows/_reusable-dependency-scan.yml
    with: { java-version: ${{ env.JAVA_VERSION }} }

  push-to-ecr:
    needs: [build, unit-tests, integration-tests, sonarqube-scan, dependency-scan]
    if: github.event_name == 'push' && (github.ref == 'refs/heads/main' || github.ref == 'refs/heads/develop')
    uses: ./.github/workflows/_reusable-push-ecr.yml
    with:
      ecr-registry: ${{ vars.AWS_ACCOUNT_ID }}.dkr.ecr.${{ env.AWS_REGION }}.amazonaws.com
      ecr-repository: ${{ env.ECR_REPOSITORY }}
      image-tag: ${{ env.IMAGE_TAG }}
      aws-region: ${{ env.AWS_REGION }}
      aws-account-id: ${{ vars.AWS_ACCOUNT_ID }}

  deploy-dev:
    needs: push-to-ecr
    if: github.ref == 'refs/heads/develop' || github.ref == 'refs/heads/main'
    uses: ./.github/workflows/_reusable-deploy.yml
    with:
      environment: dev
      ecr-registry: ${{ vars.AWS_ACCOUNT_ID }}.dkr.ecr.${{ env.AWS_REGION }}.amazonaws.com
      ecr-repository: ${{ env.ECR_REPOSITORY }}
      image-tag: ${{ env.IMAGE_TAG }}
      aws-region: ${{ env.AWS_REGION }}
      aws-account-id: ${{ vars.AWS_ACCOUNT_ID }}
      cluster-name: ${{ vars.DEPLOY_DEV_CLUSTER }}
      namespace: spring-boot-app-dev
      values-file: ${{ env.HELM_CHART_PATH }}/values-dev.yaml
      smoke-test-scheme: http
      timeout-minutes: 10
    secrets: inherit

  deploy-prod:
    needs: deploy-dev
    if: github.ref == 'refs/heads/main'
    uses: ./.github/workflows/_reusable-deploy.yml
    with:
      environment: prod
      ecr-registry: ${{ vars.AWS_ACCOUNT_ID }}.dkr.ecr.${{ env.AWS_REGION }}.amazonaws.com
      ecr-repository: ${{ env.ECR_REPOSITORY }}
      image-tag: ${{ env.IMAGE_TAG }}
      aws-region: ${{ env.AWS_REGION }}
      aws-account-id: ${{ vars.AWS_ACCOUNT_ID }}
      cluster-name: ${{ vars.DEPLOY_PROD_CLUSTER }}
      namespace: spring-boot-app-prod
      values-file: ${{ env.HELM_CHART_PATH }}/values-prod.yaml
      smoke-test-scheme: https
      smoke-test-host: app.example.com
      blue-green: true
      timeout-minutes: 15
      app-url: https://app.example.com
    secrets: inherit

  rollback-validation:
    needs: deploy-prod
    if: always() && needs.deploy-prod.result == 'success'
    uses: ./.github/workflows/_reusable-rollback.yml
    with:
      aws-region: ${{ env.AWS_REGION }}
      aws-account-id: ${{ vars.AWS_ACCOUNT_ID }}
      cluster-name: ${{ vars.DEPLOY_PROD_CLUSTER }}
      namespace: spring-boot-app-prod
      app-url: app.example.com
```

### `.github/workflows/pr-validation.yml` (thin caller)

```yaml
name: PR Validation

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main, develop]

concurrency:
  group: pr-validation-${{ github.event.pull_request.number }}
  cancel-in-progress: true

permissions:
  contents: read
  pull-requests: write
  security-events: write

env:
  JAVA_VERSION: '17'

jobs:
  unit-tests:
    name: 🧪 Unit Tests
    uses: ./.github/workflows/_reusable-unit-tests.yml
    with: { java-version: ${{ env.JAVA_VERSION }} }

  integration-tests:
    name: 🔗 Integration Tests
    uses: ./.github/workflows/_reusable-integration-tests.yml
    with: { java-version: ${{ env.JAVA_VERSION }} }

  dependency-scan:
    name: 📚 Dependency Scan
    uses: ./.github/workflows/_reusable-dependency-scan.yml
    with: { java-version: ${{ env.JAVA_VERSION }} }
```

---

## Reusable Workflows (summary)

| File | What it does |
|------|--------------|
| `_reusable-build.yml` | Maven package + Docker build (no push) |
| `_reusable-unit-tests.yml` | Surefire (`*Test, !*IT`) |
| `_reusable-integration-tests.yml` | Failsafe (`*IT`) + Postgres + Redis service containers |
| `_reusable-sonarqube.yml` | SonarQube scan + quality gate (requires `SONAR_TOKEN`) |
| `_reusable-dependency-scan.yml` | OWASP dependency-check (fails on CVSS ≥ 7) |
| `_reusable-push-ecr.yml` | Downloads JAR, builds & pushes image, ECR scan + Trivy |
| `_reusable-deploy.yml` | Parameterized Helm deploy (dev OR prod via `environment` input) |
| `_reusable-rollback.yml` | Validates rollback strategy, generates `ROLLBACK.md` |

---

## Composite Action

### `.github/actions/slack-notify`

Posts deployment status to Slack. Takes `status`, `environment`, `image-tag`, `commit-sha`, `author`, `run-id`, `run-url`, and optional `app-url`. Picks the right header text + emoji from the `(status, environment)` pair, so callers don't carry around three copy-pasted JSON payloads.

---

## Required GitHub Secrets

| Secret | Used by |
|--------|---------|
| `SONAR_TOKEN` | `_reusable-sonarqube.yml` (passed via `secrets: inherit`) |
| `SLACK_WEBHOOK_URL` | `_reusable-deploy.yml` (passed via `secrets: inherit`) |
| `GPG_PUBLIC_KEY` | `_reusable-build.yml` (optional) |

## Required GitHub Variables

| Variable | Value |
|----------|-------|
| `AWS_ACCOUNT_ID` | `<your-aws-account-id>` |
| `DEPLOY_DEV_CLUSTER` | `dev-eks-cluster` |
| `DEPLOY_PROD_CLUSTER` | `prod-eks-cluster` |

## Required GitHub Environments

- **`dev`** — no protection (auto-deploys on merge to `develop`)
- **`prod`** — 2 required reviewers, 5-min wait timer, branch: `main` only

## Pipeline Stages Summary

1. **Build** — Checkout, Java 17, Maven package, Docker image build with cache (via `_reusable-build.yml`)
2. **Test (parallel)** — Unit, Integration, SonarQube, OWASP dependency scan
3. **Push to ECR** — ECR push, ECR native scan, Trivy final scan (fails on CRITICAL)
4. **Deploy Dev** — Helm install/upgrade, verify, smoke tests, Slack notify
5. **Deploy Prod** — Manual approval gate, blue-green Helm deploy, prod smoke tests
6. **Rollback Validation** — Verify Helm history, dry-run rollback, generate runbook
