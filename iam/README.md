# AWS IAM Configuration for GitHub Actions OIDC

This document describes how to set up AWS IAM for the GitHub Actions CI/CD pipeline using OpenID Connect (OIDC) — **no long-lived AWS access keys required**.

## Architecture

```
GitHub Actions Workflow
        │
        │ (OIDC token from GitHub)
        ▼
AWS STS (AssumeRoleWithWebIdentity)
        │
        │ (temporary credentials, 1-hour TTL)
        ▼
IAM Role: github-actions-eks-deploy
        │
        ├── ECR: push images, scan
        ├── EKS: describe clusters
        ├── Secrets Manager: fetch secrets
        └── SSM: fetch parameters
```

## Prerequisites

1. AWS account with admin access to create IAM resources
2. Existing EKS cluster(s) in `us-east-1`
3. Existing ECR repository
4. GitHub repository: `org/spring-boot-app` (replace `org` with your org name)

## Step 1: Create OIDC Identity Provider in IAM

```bash
# Run once per AWS account
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b2c17b3b32f0b1d4b9e8c2d3a4e5f6a7b8c9d0e1f2
```

## Step 2: Create IAM Role

```bash
# Create the role
aws iam create-role \
  --role-name github-actions-eks-deploy \
  --assume-role-policy-document file://trust-policy.json \
  --description "GitHub Actions role for Spring Boot app CI/CD"

# Attach the policy
aws iam put-role-policy \
  --role-name github-actions-eks-deploy \
  --policy-name github-actions-eks-deploy-policy \
  --policy-document file://policy.json

# Get the role ARN (you'll need this in GitHub)
aws iam get-role --role-name github-actions-eks-deploy \
  --query 'Role.Arn' --output text
```

> **Important:** Replace `<ACCOUNT_ID>` in `trust-policy.json` with your actual AWS account ID before running the command above.

## Step 3: EKS Access Configuration

Since the role is now used by GitHub Actions (running outside the cluster), we need to grant it access to EKS. There are two approaches:

### Option A: IAM Access Entries (recommended, EKS 1.23+)

```bash
# Create access entry
aws eks create-access-entry \
  --cluster-name prod-eks-cluster \
  --principal-arn arn:aws:iam::<ACCOUNT_ID>:role/github-actions-eks-deploy \
  --type STANDARD

# Grant ClusterAdmin (or a custom least-privilege policy)
aws eks associate-access-policy \
  --cluster-name prod-eks-cluster \
  --principal-arn arn:aws:iam::<ACCOUNT_ID>:role/github-actions-eks-deploy \
  --access-scope cluster \
  --access-policy AmazonEKSClusterAdminPolicy
```

### Option B: aws-auth ConfigMap (legacy)

Add to `aws-auth` ConfigMap:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: aws-auth
  namespace: kube-system
data:
  mapRoles: |
    - rolearn: arn:aws:iam::<ACCOUNT_ID>:role/github-actions-eks-deploy
      username: github-actions
      groups:
        - system:masters
```

## Step 4: Required EKS Cluster Permissions

The role needs these Kubernetes RBAC permissions inside the cluster:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: github-actions-deployer
rules:
  - apiGroups: [""]
    resources: ["namespaces", "configmaps", "secrets", "serviceaccounts", "events"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["apps"]
    resources: ["deployments", "replicasets", "statefulsets", "daemonsets"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["networking.k8s.io"]
    resources: ["ingresses", "networkpolicies"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["autoscaling"]
    resources: ["horizontalpodautoscalers"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["policy"]
    resources: ["poddisruptionbudgets"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: ["monitoring.coreos.com"]
    resources: ["servicemonitors", "podmonitors"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
  - apiGroups: [""]
    resources: ["pods", "pods/log", "services", "endpoints"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: github-actions-deployer
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: github-actions-deployer
subjects:
  - kind: User
    name: github-actions  # matches aws-auth username
    apiGroup: rbac.authorization.k8s.io
```

## Step 5: ECR Repository Setup

```bash
# Create ECR repository
aws ecr create-repository \
  --repository-name spring-boot-app \
  --image-scanning-configuration scanOnPush=true \
  --encryption-configuration encryptionType=AES256

# Apply lifecycle policy
aws ecr put-lifecycle-policy \
  --repository-name spring-boot-app \
  --lifecycle-policy-text file://ecr-lifecycle-policy.json
```

## Step 6: OIDC Trust Conditions Explained

The `trust-policy.json` restricts the role to:
- Specific **branches**: `main`, `develop`
- Specific **environments**: `dev`, `prod` (GitHub Environments)
- **Pull requests** (read-only, but scoped to this repo only)

This means a compromised branch cannot assume the role. Each environment gets its own scoped trust, so a PR deployment can't trigger a production environment.

## Verification

```bash
# Get an OIDC token (in GitHub Actions context)
# This is what the workflow does automatically via aws-actions/configure-aws-credentials

# Test from a developer machine (won't work — that's the point!)
aws sts assume-role-with-web-identity \
  --role-arn arn:aws:iam::<ACCOUNT_ID>:role/github-actions-eks-deploy \
  --role-session-name test \
  --web-identity-token <github-oidc-token>
# Expected: AccessDenied
```

## Cost

- OIDC provider: **Free**
- IAM role: **Free**
- STS AssumeRole calls: **Free**

## Cleanup

```bash
# Remove role and policy
aws iam delete-role-policy \
  --role-name github-actions-eks-deploy \
  --policy-name github-actions-eks-deploy-policy
aws iam delete-role --role-name github-actions-eks-deploy
```