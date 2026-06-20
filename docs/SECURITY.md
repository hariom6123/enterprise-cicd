# Security Best Practices

## Threat Model

This document covers the security controls applied at every layer of the CI/CD pipeline, aligned with the **OWASP CI/CD Top 10** and **AWS Well-Architected Framework — Security Pillar**.

### CI/CD Attack Surface

| Stage | Threat | Mitigation |
|-------|--------|------------|
| Source | Malicious commit | Branch protection, signed commits, CODEOWNERS |
| Build | Compromised dependency | OWASP, Dependabot, pinned versions |
| Build | Malicious code in build | SonarQube SAST, code review |
| Image | Backdoored base image | Multi-stage, distroless base, image scanning |
| Registry | Image tampering | ECR push encryption, ECR scan, image signing |
| Deploy | Credential theft | OIDC (no long-lived keys) |
| Deploy | Unauthorized deploy | Environment protection rules, branch restrictions |
| Runtime | Container escape | Non-root, read-only FS, drop ALL caps, seccomp |
| Runtime | Lateral movement | Network Policies, Pod Security Standards |
| Runtime | Secret exposure | External Secrets Operator, no K8s Secrets |

---

## 1. Source Code Security

### Branch Protection Rules (GitHub Settings → Branches)

For `main`:
- ✅ Require a pull request before merging
  - Required approvals: **2**
  - Dismiss stale pull request approvals
  - Require review from Code Owners
- ✅ Require status checks to pass before merging
  - `build`, `unit-tests`, `integration-tests`, `sonarqube-scan`, `dependency-scan`
- ✅ Require signed commits
- ✅ Require linear history
- ✅ Include administrators (no bypass)
- ✅ Restrict who can push: maintainers only

### Signed Commits

```bash
# Developer machine
gpg --gen-key
gpg --list-secret-keys --keyid-format=long
# Add public key to GitHub: Settings → SSH and GPG keys

# Configure git
git config --global user.signingkey <KEY_ID>
git config --global commit.gpgsign true
git config --global tag.gpgsign true
```

### Secret Scanning

Enabled by default on public repos. For private repos, enable:
- **GitHub Secret Scanning** (Settings → Code security and analysis)
- **Push Protection** (blocks commits with secrets)
- **gitleaks** as a pre-commit hook:

```yaml
# .pre-commit-config.yaml
repos:
  - repo: https://github.com/gitleaks/gitleaks
    rev: v8.18.0
    hooks:
      - id: gitleaks
```

---

## 2. Build Security

### Dependency Management

```xml
<!-- pom.xml: pin versions explicitly -->
<plugin>
  <groupId>org.owasp</groupId>
  <artifactId>dependency-check-maven</artifactId>
  <version>12.1.1</version>
  <configuration>
    <failBuildOnCVSS>7</failBuildOnCVSS>
  </configuration>
</plugin>
```

### Reproducible Builds

- Pin base image digest: `eclipse-temurin:21-jre-jammy@sha256:...`
- Use `--provenance=true` in Docker build
- Generate SBOM at build time (CycloneDX/SPDX)
- Sign images with **Cosign**:

```bash
# Sign image after push
cosign sign --key cosign.key \
  <account>.dkr.ecr.us-east-1.amazonaws.com/spring-boot-app:$SHA
```

### Build Cache Security

- GHA cache is encrypted at rest
- Don't cache secrets or credentials
- Use `--mount=type=cache` in Dockerfile for fine-grained control

---

## 3. Image Security

### Base Image

```dockerfile
# Use JRE-only (not JDK) — no compiler, no dev tools
FROM eclipse-temurin:21-jre-jammy AS runtime

# Pin to digest in production
# FROM eclipse-temurin:21-jre-jammy@sha256:abc123...
```

### Layered Image (defense in depth)

```dockerfile
# Copy layers in order of least-to-most frequently changing
COPY --chown=spring:spring extracted/dependencies/ ./
COPY --chown=spring:spring extracted/spring-boot-loader/ ./
COPY --chown=spring:spring extracted/snapshot-dependencies/ ./
COPY --chown=spring:spring extracted/application/ ./
```

### Scanning (multiple points)

1. **Pre-push** (Trivy) — informational only, doesn't fail
2. **ECR native scan** (after push) — fail on CRITICAL
3. **Trivy final scan** (after push) — fail on CRITICAL

### Image Scanning Configuration

```hcl
# ECR scan-on-push
resource "aws_ecr_repository" "app" {
  name                 = "spring-boot-app"
  image_tag_mutability = "IMMUTABLE"  # Prevent tag mutation
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
}
```

---

## 4. Runtime Security

### Pod Security Standards (restricted profile)

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: spring-boot-app-prod
  labels:
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/enforce-version: latest
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/warn: restricted
```

### Security Context (applied in Helm chart)

```yaml
podSecurityContext:
  runAsNonRoot: true
  runAsUser: 10001
  runAsGroup: 10001
  fsGroup: 10001
  seccompProfile:
    type: RuntimeDefault

containerSecurityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  runAsNonRoot: true
  runAsUser: 10001
  capabilities:
    drop:
      - ALL
```

### Network Policies (zero-trust)

```yaml
# Default deny all
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
spec:
  podSelector: {}
  policyTypes:
    - Ingress
    - Egress
---
# Allow specific ingress from ingress controller
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-ingress
spec:
  podSelector:
    matchLabels:
      app: spring-boot-app
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              name: ingress
      ports:
        - protocol: TCP
          port: 8080
```

### Secrets Management

❌ **Don't:** Store secrets in K8s Secrets (base64 encoded, not encrypted)
✅ **Do:** Use External Secrets Operator + AWS Secrets Manager

```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: spring-boot-app-secrets
spec:
  secretStoreRef:
    name: aws-secrets-manager
    kind: ClusterSecretStore
  target:
    name: spring-boot-app-secrets
    creationPolicy: Owner
  data:
    - secretKey: SPRING_DATASOURCE_PASSWORD
      remoteRef:
        key: prod/spring-boot-app/db
        property: password
```

### IAM Roles for Service Accounts (IRSA)

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: spring-boot-app
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::<ACCOUNT_ID>:role/spring-boot-app-sa-role
```

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {
      "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/oidc.eks.us-east-1.amazonaws.com/id/<ID>"
    },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "oidc.eks.us-east-1.amazonaws.com/id/<ID>:sub": "system:serviceaccount:spring-boot-app-prod:spring-boot-app"
      }
    }
  }]
}
```

---

## 5. Pipeline Security

### OIDC Authentication (no long-lived keys)

The workflow uses OIDC tokens to assume IAM roles — credentials are short-lived (1 hour) and automatically rotated.

**Trust policy conditions** restrict the role to:
- Specific branches (`main`, `develop`)
- Specific environments (`dev`, `prod`)
- Specific repository (no cross-repo access)

### Workflow Permissions (least privilege)

```yaml
permissions:
  contents: read
  id-token: write  # Only for OIDC
  pull-requests: write
  security-events: write
```

### Concurrency Control

```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}
```

Prevents:
- Race conditions on deploys
- Resource exhaustion from parallel runs
- Cancel-in-progress for non-main branches (avoid canceling critical prod deploys)

### Audit Trail

All pipeline events are logged in:
- **GitHub Actions logs** (90 days)
- **CloudTrail** (AWS API calls) — indefinite (with S3 archival)
- **ECR scan results** (long-term)

---

## 6. Compliance Considerations

### SOC 2

| Control | Implementation |
|---------|----------------|
| Access control | OIDC + RBAC + least-privilege IAM |
| Audit logging | GitHub audit log + CloudTrail |
| Change management | Branch protection + PR approvals |
| Encryption | ECR AES-256, TLS in transit |
| Vulnerability management | Trivy + ECR scan + SonarQube + OWASP |

### PCI-DSS (if applicable)

- Network segmentation via Network Policies
- Encryption at rest (EBS gp3 encrypted, ECR encrypted)
- Encryption in transit (TLS 1.2+)
- Access logging (VPC flow logs, ALB access logs)
- Regular vulnerability scanning

### GDPR

- PII handling: data residency in `us-east-1` only
- Right to erasure: documented runbook for data deletion
- Data minimization: only collect metrics/logs needed

---

## 7. Security Checklist (Pre-Production)

- [ ] All GitHub secrets encrypted
- [ ] Branch protection enabled on `main`
- [ ] CODEOWNERS file configured
- [ ] OIDC trust policy restricts to specific repo + branches
- [ ] Container runs as non-root
- [ ] Read-only root filesystem
- [ ] All capabilities dropped
- [ ] Network Policies deny by default
- [ ] Pod Security Standards enforced
- [ ] ECR scan-on-push enabled
- [ ] Trivy scan in pipeline (fails on CRITICAL)
- [ ] OWASP dependency check (fails on CVSS ≥ 7)
- [ ] SonarQube quality gate enforced
- [ ] Secrets via External Secrets Operator (not K8s Secrets)
- [ ] IRSA configured for pod-level AWS access
- [ ] Image signing (Cosign) — recommended
- [ ] mTLS between services (service mesh) — recommended
- [ ] Runtime threat detection (Falco / GuardDuty) — recommended

---

## 8. Incident Response

### Suspected Image Compromise

```bash
# 1. Stop deployments
kubectl scale deployment spring-boot-app -n spring-boot-app-prod --replicas=0

# 2. Roll back to last known good
helm rollback spring-boot-app -n spring-boot-app-prod

# 3. Investigate
kubectl get events -n spring-boot-app-prod --sort-by='.lastTimestamp'
kubectl logs -n spring-boot-app-prod -l app.kubernetes.io/name=spring-boot-app --previous

# 4. Scan prior images
aws ecr start-image-scan --repository-name spring-boot-app --image-id imageTag=<prior-sha>

# 5. Notify team
# Use Slack: #incident-response
```

### Suspected Credential Leak

```bash
# 1. Rotate immediately in AWS Secrets Manager
aws secretsmanager rotate-secret --secret-id prod/spring-boot-app/db

# 2. Force pod restart to pick up new secrets
kubectl rollout restart deployment spring-boot-app -n spring-boot-app-prod

# 3. Revoke GitHub PAT / token
# GitHub Settings → Developer settings → Personal access tokens

# 4. Audit CloudTrail for suspicious activity
aws cloudtrail lookup-events --lookup-attributes AttributeKey=Username,AttributeValue=<compromised-user>
```

### Security Contact

- **Email:** security@example.com
- **Slack:** #security-incidents
- **On-call:** PagerDuty — `security-oncall`

---

## 9. Recommended Additional Controls

| Tool | Purpose | Cost |
|------|---------|------|
| **Falco** | Runtime threat detection | Free |
| **OPA/Gatekeeper** | Policy enforcement | Free |
| **Kyverno** | Alternative to OPA | Free |
| **Cosign** | Image signing | Free |
| **Trivy Operator** | Continuous cluster scanning | Free |
| **GuardDuty** | AWS-native threat detection | $$ |
| **Prisma Cloud** | Full CSPM | $$$ |
| **Snyk** | Developer-first security | $$ |

---

## 10. References

- [OWASP CI/CD Top 10](https://owasp.org/www-project-top-10-ci-cd-security/)
- [AWS Well-Architected — Security Pillar](https://docs.aws.amazon.com/wellarchitected/latest/security-pillar/welcome.html)
- [CIS Kubernetes Benchmark](https://www.cisecurity.org/benchmark/kubernetes)
- [NIST 800-190 — Container Security](https://nvlpubs.nist.gov/nistpubs/SpecialPublications/NIST.SP.800-190.pdf)
- [Pod Security Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/)