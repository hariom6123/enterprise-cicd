# Cost Optimization Recommendations

## Executive Summary

A typical enterprise Spring Boot app on EKS costs **$8,000–$12,000/month** at moderate scale. Following these recommendations, you can reduce that by **35–50%** while improving performance and reliability.

**Target monthly cost:** $5,000–$6,000 (savings of $3,000–$6,000)

---

## 1. Compute Optimization

### 1.1 Spot Instances for Non-Production

**Savings:** 60–90% on EC2/instance costs

```yaml
# Karpenter provisioner for dev/test
apiVersion: karpenter.sh/v1alpha1
kind: Provisioner
metadata:
  name: dev-cluster
spec:
  requirements:
    - key: karpenter.sh/capacity-type
      operator: In
      values: ["spot"]  # Use spot for dev
  provider:
    instanceTypes:
      - m5.large
      - m5.xlarge
      - m6i.large
```

### 1.2 Spot + Graviton for Production (stateful workloads only on Spot)

**Savings:** 20–40% on instance costs

```yaml
# Prod provisioner
apiVersion: karpenter.sh/v1alpha1
kind: Provisioner
metadata:
  name: prod-cluster
spec:
  requirements:
    - key: karpenter.sh/capacity-type
      operator: In
      values: ["on-demand", "spot"]
    - key: karpenter.k8s.aws/instance-family
      operator: In
      values: ["m6g", "m7g", "c6g", "c7g"]  # Graviton (ARM)
```

### 1.3 Right-Sizing with VPA

**Savings:** 20–40% by matching requests to actual usage

```yaml
apiVersion: autoscaling.k8s.io/v1
kind: VerticalPodAutoscaler
metadata:
  name: spring-boot-app-vpa
spec:
  targetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: spring-boot-app
  updatePolicy:
    updateMode: "Auto"
  resourcePolicy:
    containerPolicies:
      - containerName: '*'
        minAllowed:
          cpu: 100m
          memory: 256Mi
        maxAllowed:
          cpu: 4
          memory: 8Gi
```

### 1.4 Cluster Autoscaler / Karpenter

**Savings:** 15–30% by scaling nodes down during off-hours

```yaml
# Karpenter scales nodes to zero during off-hours for dev
# Schedule via cron or use it just-in-time provisioning
```

### 1.5 HPA Tuning

```yaml
# Don't over-provision
autoscaling:
  minReplicas: 3   # Not 5+ unless needed
  maxReplicas: 10  # Cap to prevent runaway
  targetCPUUtilizationPercentage: 70  # Aggressive
```

---

## 2. Storage Optimization

### 2.1 EBS gp3 Instead of gp2

**Savings:** 20% with better performance

```yaml
# Storage class for EBS
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: gp3
provisioner: ebs.csi.aws.com
parameters:
  type: gp3
  iops: "3000"
  throughput: "125"
  fsType: ext4
```

### 2.2 ECR Lifecycle Policy

**Savings:** Variable — typically $50–$200/month

```json
{
  "rules": [
    {
      "rulePriority": 1,
      "description": "Keep last 30 tagged images",
      "selection": {
        "tagStatus": "tagged",
        "countType": "imageCountMoreThan",
        "countNumber": 30
      },
      "action": {"type": "expire"}
    },
    {
      "rulePriority": 2,
      "description": "Remove untagged after 7 days",
      "selection": {
        "tagStatus": "untagged",
        "countType": "sinceImagePushed",
        "countUnit": "days",
        "countNumber": 7
      },
      "action": {"type": "expire"}
    }
  ]
}
```

### 2.3 S3 Intelligent-Tiering

**Savings:** 30–50% on S3 storage

```bash
# Apply to log buckets
aws s3api put-bucket-intelligent-tiering-configuration \
  --bucket my-logs-bucket \
  --id EntireBucket \
  --intelligent-tiering-configuration '{
    "Id": "EntireBucket",
    "Status": "Enabled",
    "Tierings": [
      {"Days": 90, "AccessTier": "ARCHIVE_ACCESS"},
      {"Days": 180, "AccessTier": "DEEP_ARCHIVE_ACCESS"}
    ]
  }'
```

---

## 3. Network Optimization

### 3.1 VPC Endpoints (avoid NAT gateway data charges)

**Savings:** $0.045/GB for cross-AZ data transfer

```hcl
# Terraform
resource "aws_vpc_endpoint" "ecr_api" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.us-east-1.ecr.api"
  vpc_endpoint_type = "Interface"
  subnet_ids        = aws_subnet.private[*].id
}

resource "aws_vpc_endpoint" "ecr_dkr" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.us-east-1.ecr.dkr"
  vpc_endpoint_type = "Interface"
  subnet_ids        = aws_subnet.private[*].id
}

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.us-east-1.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.private[*].id
}
```

### 3.2 CloudFront for Static Assets

**Savings:** Cache at edge, reduce origin data transfer

```yaml
# CloudFront distribution for static content
Origins:
  - DomainName: spring-boot-alb-1234567890.us-east-1.elb.amazonaws.com
    OriginPath: /static
    CustomOriginConfig:
      HTTPPort: 80
      OriginProtocolPolicy: https-only
```

---

## 4. GitHub Actions Cost Optimization

### 4.1 Concurrency Control

```yaml
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}
```

Saves minutes by canceling redundant runs on PRs.

### 4.2 Caching

Already configured: Maven cache, Docker Buildx cache. Saves ~3-5 minutes per build.

### 4.3 Self-Hosted Runners for High Volume

If you exceed 50,000 minutes/month, self-hosted runners on Spot instances are cheaper.

---

## 5. Observability Cost Optimization

### 5.1 Log Retention

```yaml
# CloudWatch log group retention
resource "aws_cloudwatch_log_group" "app" {
  name              = "/aws/eks/spring-boot-app"
  retention_in_days = 30  # Not 365
}
```

### 5.2 Metrics Cardinality

Don't add unbounded labels (user IDs, request IDs) to metrics — they explode storage costs.

### 5.3 Sampling for Traces

```java
// 10% sampling for traces (not 100%)
@Bean
public Sampler defaultSampler() {
    return Sampler.create(0.1f);
}
```

---

## 6. Dev Environment Cost Control

### 6.1 Scale Down Off-Hours

Use a CronJob or KEDA scaler to scale dev deployments to zero on nights/weekends.

```yaml
# Example: scale down at 7pm
apiVersion: batch/v1
kind: CronJob
metadata:
  name: scale-down-dev
spec:
  schedule: "0 19 * * 1-5"  # 7 PM weekdays
  jobTemplate:
    spec:
      template:
        spec:
          containers:
          - name: kubectl
            image: bitnami/kubectl
            command: ["kubectl", "scale", "deployment", "spring-boot-app",
                      "-n", "spring-boot-app-dev", "--replicas=0"]
```

### 6.2 Single AZ for Dev

```yaml
# Dev cluster topology spread constraint
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: topology.kubernetes.io/zone
    whenUnsatisfiable: DoNotSchedule
    labelSelector:
      matchLabels:
        app: spring-boot-app
```

**Savings:** Skip multi-AZ for dev

### 6.3 Use Smaller Instance Types

```yaml
# Dev values
resources:
  requests:
    cpu: 250m
    memory: 512Mi  # Smaller than prod
  limits:
    cpu: 1000m
    memory: 1Gi
```

---

## 7. Reserved Instances / Savings Plans

### Compute Savings Plans (recommended)

- **1-year:** ~27% discount
- **3-year:** ~45% discount

### EC2 Instance Savings Plans (specific instance family)

- **1-year:** ~30% discount
- **3-year:** ~50% discount

### When to Use Reserved vs Savings Plans

| Workload | Recommendation |
|----------|----------------|
| Stable prod baseline | 3-year Compute Savings Plan |
| Bursty prod workloads | On-Demand or Spot |
| Dev/test | Spot only |

---

## 8. Estimated Monthly Savings Summary

| Optimization | Monthly Savings | Effort |
|--------------|-----------------|--------|
| Spot for dev | $800 | Low |
| Graviton for prod | $600 | Medium |
| VPA right-sizing | $400 | Low |
| Cluster autoscaler | $300 | Low |
| EBS gp3 | $200 | Low |
| ECR lifecycle | $100 | Low |
| S3 Intelligent-Tiering | $150 | Low |
| VPC Endpoints | $250 | Medium |
| Dev off-hours scaling | $2,000 | Medium |
| Log retention tuning | $200 | Low |
| 3-year Savings Plan (prod) | $1,500 | Low |
| **Total** | **~$6,500** | — |

### Reduction: 35-50% of typical EKS bill

---

## 9. Cost Monitoring

### 9.1 AWS Cost Anomaly Detection

```bash
aws ce create-anomaly-monitor \
  --anomaly-monitor "{
    \"MonitorName\": \"EKS-Production\",
    \"MonitorType\": \"DIMENSIONAL\",
    \"MonitorDimension\": \"SERVICE\",
    \"MonitorSpecification\": \"{\\\"Dimensions\\\": {\\\"Key\\\": \\\"SERVICE\\\", \\\"Values\\\": [\\\"Amazon Elastic Kubernetes Service\\\"]}}\"
  }"
```

### 9.2 Kubecost (Recommended)

Install Kubecost in your cluster to get real-time cost allocation by namespace, deployment, label.

```bash
helm install kubecost cost-analyzer \
  --repo https://kubecost.github.io/cost-analyzer/ \
  --namespace kubecost --create-namespace
```

### 9.3 Cost Allocation Tags

```hcl
# Tag all resources
tags = {
  Environment = var.environment
  Team        = var.team
  Project     = "spring-boot-app"
  ManagedBy   = "terraform"
}
```

---

## 10. Cost Optimization Roadmap

### Quarter 1: Quick Wins (saves ~$1,500/month)
- [ ] ECR lifecycle policies
- [ ] EBS gp3 migration
- [ ] Dev off-hours scaling
- [ ] Log retention tuning

### Quarter 2: Architecture Changes (saves ~$3,000/month)
- [ ] Karpenter + Spot for dev
- [ ] VPA for right-sizing
- [ ] VPC Endpoints
- [ ] S3 Intelligent-Tiering

### Quarter 3: Strategic (saves ~$2,000/month)
- [ ] Graviton migration
- [ ] 1-year Savings Plan
- [ ] Reserved Instances for baseline
- [ ] CloudFront for static assets

### Total potential savings: $6,500/month

---

## 11. Cost Optimization Checklist

- [ ] ECR lifecycle policy applied
- [ ] EBS volumes using gp3
- [ ] Spot instances for dev/test
- [ ] Graviton instances in prod
- [ ] VPA configured for right-sizing
- [ ] Cluster autoscaler / Karpenter enabled
- [ ] VPC endpoints for AWS services
- [ ] S3 Intelligent-Tiering on log buckets
- [ ] Log retention ≤ 90 days
- [ ] Dev clusters scale to zero off-hours
- [ ] Cost anomaly detection enabled
- [ ] Kubecost or equivalent installed
- [ ] Cost allocation tags applied
- [ ] Reserved Instances / Savings Plan analyzed
- [ ] HPA tuned (min/max replicas, targets)
- [ ] Resource requests/limits reviewed quarterly
- [ ] Unused AWS resources cleaned up monthly