# k8s/base/

Cluster-wide Kubernetes manifests applied once per cluster by a cluster
administrator, **out of band of the Helm release**.

| File | Purpose |
|------|---------|
| `github-actions-deployer.clusterrole.yaml` | RBAC for the GitHub Actions deployer role |
| `github-actions-deployer.clusterrolebinding.yaml` | Binds the role to the OIDC principal |

## Apply

```bash
kubectl apply -f k8s/base/
```

This must be applied **before** the first pipeline run on a new cluster;
without it, `helm upgrade` and the smoke-test verification steps fail with
`Forbidden` errors.

## Namespace manifests

Namespace manifests live separately in `k8s/dev/` and `k8s/prod/`. They
are applied automatically by the Helm release (`--create-namespace`) and
exist as standalone files for ops review and policy enforcement.
