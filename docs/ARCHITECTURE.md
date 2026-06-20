# Enterprise CI/CD Platform — Design Document

**Project:** Spring Boot Application on AWS EKS
**Author:** Enterprise CI/CD Platform Architect
**Date:** 2026-06-18
**Version:** 1.0

---

## 📋 Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Repository Structure](#repository-structure)
3. [Pipeline Stages](#pipeline-stages)
4. [AWS IAM Configuration](#aws-iam-configuration)
5. [GitHub Configuration](#github-configuration)
6. [Security Best Practices](#security-best-practices)
7. [Cost Optimization](#cost-optimization)
8. [Monitoring Strategy](#monitoring-strategy)
9. [Rollback Procedures](#rollback-procedures)
10. [Implementation Checklist](#implementation-checklist)

---

## 1. Architecture Overview

### High-Level Flow

```
┌─────────────┐     ┌──────────────────────────────────────┐     ┌─────────────┐
│   GitHub    │────▶│   GitHub Actions (OIDC Auth)        │────▶│  AWS ECR    │
│  Repository │     │  ┌────────┬─────────┬──────────┐    │     └─────────────┘
└─────────────┘     │  │ Build  │  Test   │  Deploy  │    │             │
                    │  └────────┴─────────┴──────────┘    │             ▼
                    └──────────────────────────────────────┘     ┌─────────────┐
                              │           │          │           │  AWS EKS    │
                              ▼           ▼          ▼           │  (Dev/Prod) │
                          ┌──────┐  ┌─────────┐  ┌────────┐     └─────────────┘
                          │ JAR  │  │SonarQube│  │  Helm  │            │
                          │Artifact│ │Trivy    │  │ Charts │            ▼
                          └──────┘  └─────────┘  └────────┘     ┌─────────────┐
                                                              │  Slack      │
                                                              │  Notify     │
                                                              └─────────────┘
```

### Team Responsibilities

| Role | Responsibility |
|------|----------------|
| **Platform Architect** | Pipeline integration, OIDC, cost, monitoring |
| **Build Engineer** | Maven, Docker, ECR, JAR artifacts |
| **Test Engineer** | Unit/IT tests, SonarQube, Trivy, OWASP |
| **Deploy Engineer** | Helm charts, K8s manifests, EKS deploys |

---

## 2. Repository Structure

```
spring-boot-app/
├── .github/
│   └── workflows/
│       ├── ci-cd-pipeline.yml          # Main pipeline (THIS FILE)
│       └── pr-validation.yml           # PR-only checks
├── charts/
│   └── spring-boot-app/
│       ├── Chart.yaml
│       ├── values.yaml                 # Default values
│       ├── values-dev.yaml             # Dev overrides
│       ├── values-prod.yaml            # Prod overrides
│       └── templates/
│           ├── _helpers.tpl
│           ├── deployment.yaml
│           ├── service.yaml
│           ├── ingress.yaml
│           ├── hpa.yaml
│           ├── serviceaccount.yaml
│           ├── configmap.yaml
│           ├── networkpolicy.yaml
│           ├── poddisruptionbudget.yaml
│           └── servicemonitor.yaml
├── k8s/
│   ├── dev/
│   │   └── namespace.yaml
│   └── prod/
│       └── namespace.yaml
├── iam/
│   ├── trust-policy.json               # OIDC trust
│   ├── policy.json                     # IAM permissions
│   ├── ecr-lifecycle-policy.json
│   └── README.md                       # Setup instructions
├── docs/
│   ├── ARCHITECTURE.md
│   ├── SECURITY.md
│   ├── COST-OPTIMIZATION.md
│   ├── MONITORING.md
│   └── ROLLBACK.md
├── Dockerfile
├── pom.xml
└── src/
```

---

## 3. Pipeline Stages

### Stage 1: Build
- **Trigger:** Push to `main`/`develop` or PR
- **Duration:** ~5-8 minutes
- **Steps:**
  1. Checkout code (with LFS, full history for SonarQube)
  2. Setup Java 21 (Temurin) with Maven cache
  3. Maven build (skip tests — runs in test stage)
  4. Upload JAR artifact (30-day retention)
  5. Setup Docker Buildx with GHA cache
  6. Configure AWS credentials (OIDC)
  7. Build Docker image (with provenance + SBOM)
  8. Pre-push Trivy scan (informational)

### Stage 2: Test (parallel jobs)
- **Unit Tests** — Surefire reports
- **Integration Tests** — With Postgres + Redis services, Failsafe reports
- **SonarQube** — Quality gate enforcement
- **OWASP Dependency Check** — CVE scan, fail on CVSS ≥ 7

### Stage 3: Push to ECR
- **Trigger:** Push event only (not on PRs)
- **Steps:**
  1. Download JAR artifact
  2. Build & push to ECR (with cache, provenance, SBOM)
  3. ECR native scan
  4. Trivy final scan (fails on CRITICAL)
  5. Upload SARIF to GitHub Security tab

### Stage 4: Deploy to Dev EKS
- **Trigger:** Push to `develop` or `main`
- **Environment:** `dev` (no protection)
- **Steps:**
  1. Configure AWS + update kubeconfig
  2. Helm template validation
  3. Helm upgrade --install with --atomic
  4. Verify all pods Ready
  5. Smoke tests (liveness, readiness, info)
  6. Newman E2E tests
  7. Slack notification (success/failure)

### Stage 5: Deploy to Prod EKS
- **Trigger:** Push to `main` only
- **Environment:** `prod` (manual approval, 2 reviewers, 5-min wait)
- **Steps:**
  1. Pre-flight checks (current image, cluster info)
  2. Blue-green Helm deploy
  3. Wait for rollout
  4. Production smoke tests
  5. Slack notification

### Stage 6: Rollback Validation
- **Trigger:** After successful prod deploy
- **Steps:**
  1. Verify Helm history
  2. Dry-run rollback
  3. Check HPA, PDB, endpoints
  4. Generate ROLLBACK.md runbook

---

## 4. AWS IAM Configuration

See `iam/README.md` for the full setup guide. Summary:

| Resource | Purpose |
|----------|---------|
| OIDC Provider | Federated trust for GitHub |
| IAM Role `github-actions-eks-deploy` | Assumed by workflow |
| ECR Repository `spring-boot-app` | Image storage |
| EKS Cluster(s) | Target environments |

**Key principle:** OIDC authentication — no long-lived AWS keys.

---

## 5. GitHub Configuration

### Required Secrets

| Secret | Purpose |
|--------|---------|
| `AWS_ACCOUNT_ID` | AWS account ID |
| `SONAR_TOKEN` | SonarQube authentication |
| `SONAR_HOST_URL` | SonarQube server URL |
| `SLACK_WEBHOOK_URL` | Encrypted Slack webhook |
| `GPG_PUBLIC_KEY` | For signed commits (optional) |

### Required Variables

| Variable | Example Value |
|----------|---------------|
| `AWS_ACCOUNT_ID` | `123456789012` |
| `DEPLOY_DEV_CLUSTER` | `dev-eks-cluster` |
| `DEPLOY_PROD_CLUSTER` | `prod-eks-cluster` |

### Required Environments

**`dev`:**
- No required reviewers
- No wait timer
- Deployment branch: any

**`prod`:**
- Required reviewers: 2
- Wait timer: 5 minutes
- Deployment branch: `main` only

---

## 6. Security Best Practices

### Container Security ✅
- Non-root user (UID 10001)
- Read-only root filesystem
- All Linux capabilities dropped
- Seccomp RuntimeDefault profile
- Multi-stage build (no build tools in final image)
- Distroless/JRE-only base image
- Image scanning (Trivy + ECR native)
- SBOM generation at build time

### Runtime Security ✅
- Network Policies for microsegmentation
- Pod Security Standards (restricted profile)
- External Secrets Operator (no K8s secrets for sensitive data)
- IRSA (IAM Roles for Service Accounts)
- OPA/Kyverno for policy enforcement (recommended)

### Pipeline Security ✅
- OIDC authentication (no long-lived AWS keys)
- Branch protection rules (require PR reviews, status checks)
- Signed commits (GPG)
- Dependency pinning via Dependabot/Renovate
- Secret scanning (GitHub native + gitleaks)
- Least-privilege IAM roles
- Environment protection rules with manual approval for prod

### Application Security ✅
- OWASP Dependency Check
- SonarQube with quality gates
- Code coverage thresholds enforced
- SAST via SonarQube
- Container vulnerability scanning at multiple points

See `docs/SECURITY.md` for detailed threat model.

---

## 7. Cost Optimization

### Compute
| Strategy | Savings | Implementation |
|----------|---------|----------------|
| Spot Instances for dev/test | 60-90% | Karpenter + Spot |
| Savings Plans for prod | 30-50% | 1-year commit |
| Right-sizing via VPA | 20-40% | Monitor actual usage |
| ARM-based Graviton | 20-40% | Switch instance types |
| Cluster autoscaler | 15-30% | Scale down off-hours |

### Storage
| Strategy | Savings |
|----------|---------|
| EBS gp3 instead of gp2 | 20% |
| S3 Intelligent-Tiering | 30-50% |
| EFS lifecycle | 50-80% |
| ECR image cleanup (lifecycle policy) | $$ |

### Network
| Strategy | Savings |
|----------|---------|
| VPC Endpoints for ECR/S3 | Avoid NAT data charges |
| CloudFront for static assets | Cache at edge |

### Estimated Monthly Savings
- Dev cluster off-hours: ~$2,000/mo
- Spot for dev workloads: ~$800/mo
- Graviton migration: ~$600/mo
- EBS optimization: ~$200/mo
- ECR cleanup: ~$100/mo
- **Total: ~$3,700/month (35-40% of EKS bill)**

See `docs/COST-OPTIMIZATION.md` for full recommendations.

---

## 8. Monitoring Strategy

### Three Pillars

**Metrics (Prometheus + Grafana):**
- JVM: heap, GC, threads, classes
- HTTP: request rate, latency, errors
- Database: connection pool, query latency
- Custom business metrics

**Logs (Fluent Bit → CloudWatch / OpenSearch):**
- Structured JSON logging
- Correlation IDs (traceId, spanId)
- Per-namespace log streams

**Traces (AWS X-Ray / OpenTelemetry):**
- Distributed tracing across services
- Latency breakdown by service

### SLO Definition

| SLI | SLO Target | Error Budget (30d) |
|-----|------------|---------------------|
| Availability | 99.95% | 21.6 minutes |
| Latency (P99) | < 500ms | - |
| Error Rate | < 0.1% | - |
| Throughput | > 1000 RPS | - |

### Alerting Rules

```yaml
# Example: HighErrorRate
- alert: HighErrorRate
  expr: |
    (
      sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
      /
      sum(rate(http_server_requests_seconds_count[5m]))
    ) > 0.05
  for: 5m
  labels:
    severity: critical
```

See `docs/MONITORING.md` for full Grafana dashboards and alert rules.

---

## 9. Rollback Procedures

### Quick Rollback (Helm)
```bash
helm rollback spring-boot-app -n spring-boot-app-prod
helm rollback spring-boot-app <revision> -n spring-boot-app-prod
```

### Emergency Rollback (kubectl)
```bash
kubectl rollout undo deployment/spring-boot-app -n spring-boot-app-prod
kubectl rollout undo deployment/spring-boot-app --to-revision=<N> -n spring-boot-app-prod
kubectl rollout status deployment/spring-boot-app -n spring-boot-app-prod
```

### Verify Rollback
```bash
kubectl get pods -n spring-boot-app-prod -l app.kubernetes.io/name=spring-boot-app
kubectl logs -n spring-boot-app-prod -l app.kubernetes.io/name=spring-boot-app --tail=100
curl https://app.example.com/actuator/health
```

See `docs/ROLLBACK.md` for the full runbook.

---

## 10. Implementation Checklist

### Phase 1: Foundation (Week 1)
- [ ] Create GitHub repo with branch protection rules
- [ ] Configure AWS OIDC provider
- [ ] Create IAM roles with least-privilege policies
- [ ] Set up ECR repositories with lifecycle policies
- [ ] Provision EKS clusters (dev/prod) with Karpenter
- [ ] Configure GitHub environments (dev, prod)
- [ ] Set up Slack webhook

### Phase 2: Pipeline (Week 2)
- [ ] Implement GitHub Actions workflow
- [ ] Create Helm chart structure
- [ ] Write Dockerfile with multi-stage build
- [ ] Configure SonarQube server
- [ ] Set up monitoring stack (Prometheus, Grafana, Loki)

### Phase 3: Security (Week 3)
- [ ] Implement OPA/Kyverno policies
- [ ] Configure External Secrets Operator
- [ ] Set up image signing (Cosign)
- [ ] Implement network policies
- [ ] Configure IRSA for service accounts

### Phase 4: Optimization (Week 4)
- [ ] Enable Karpenter with Spot
- [ ] Configure cluster autoscaler
- [ ] Implement VPA recommendations
- [ ] Set up cost dashboards
- [ ] Configure log retention policies
