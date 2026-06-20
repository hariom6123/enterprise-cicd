# Rollback Procedure

This runbook covers three scenarios, in order of preference:

1. **Quick rollback** — Helm rolls back to the previous revision.
2. **Targeted rollback** — Helm rolls back to a specific revision.
3. **Emergency rollback** — `kubectl rollout undo` when Helm is
   unavailable or behaving badly.

Use the verification steps at the end after **any** rollback.

---

## When to Roll Back

Roll back if **any** of these are true after a deploy:

- HTTP 5xx error rate > 1% sustained for > 5 min.
- P99 latency > 2x pre-deploy baseline for > 10 min.
- Health check failing (any pod not `Ready` after 5 min).
- HikariCP exhaustion or Redis connection storm in logs.
- A specific user-visible regression reported within 30 min of deploy.

**Don't** roll back for a single failed request or a transient blip.
Check the dashboards first — see
[`docs/MONITORING.md`](MONITORING.md#7-on-call-playbook-one-pager).

---

## 1. Identify the Bad Revision

```bash
# Production
kubectl config use-context prod
helm history spring-boot-app -n spring-boot-app-prod

# Dev
helm history spring-boot-app -n spring-boot-app-dev
```

Output looks like:

```
REVISION  UPDATED                   STATUS      CHART                       APP VERSION  DESCRIPTION
1         Mon Jan  1 12:00:00 2026  superseded  spring-boot-app-1.2.0       1.0.0        Install complete
2         Mon Jan  1 13:00:00 2026  superseded  spring-boot-app-1.2.0       1.0.0        Upgrade complete
3         Mon Jan  1 14:00:00 2026  deployed    spring-boot-app-1.2.0       1.0.0        Upgrade complete
```

The `deployed` revision is the current one. Roll back to the most
recent `superseded` (here: revision 2).

---

## 2. Quick Rollback (Helm)

Rolls back to the previous revision:

```bash
helm rollback spring-boot-app -n spring-boot-app-prod
helm rollback spring-boot-app -n spring-boot-app-dev
```

The release name and namespace match the chart's defaults. If the
release was installed with a different name, replace `spring-boot-app`.

---

## 3. Targeted Rollback (Helm)

Rolls back to a specific revision (useful when several deploys have
happened since the last good one):

```bash
# Get the revision number from `helm history`
PREV=$(helm history spring-boot-app -n spring-boot-app-prod -o json | \
  jq -r '[.[] | select(.status == "superseded")] | last | .revision')
echo "Rolling back to revision $PREV"

helm rollback spring-boot-app $PREV -n spring-boot-app-prod
```

---

## 4. Emergency Rollback (kubectl)

Use this when `helm` can't reach the cluster or a Helm rollback is
stuck. **kubectl** rolls the Deployment back directly; Helm will
notice the drift on the next operation.

```bash
# Roll back to the previous ReplicaSet
kubectl rollout undo deployment/spring-boot-app -n spring-boot-app-prod

# Roll back to a specific ReplicaSet revision
kubectl rollout undo deployment/spring-boot-app --to-revision=2 -n spring-boot-app-prod

# Watch the rollout
kubectl rollout status deployment/spring-boot-app -n spring-boot-app-prod
```

---

## 5. Image-Pin Rollback (last resort)

If both Helm and kubectl rollbacks fail (e.g. the broken image is
referenced from a ConfigMap or external state), re-deploy a known-good
image directly:

```bash
# Get the previously known-good image tag from a successful CI run
GOOD_TAG=abc1234

# Re-deploy via Helm with the explicit tag
helm upgrade --install spring-boot-app ./charts/spring-boot-app \
  --values ./charts/spring-boot-app/values-prod.yaml \
  --set image.tag=$GOOD_TAG \
  --set image.repository=$ECR_REGISTRY/spring-boot-app \
  --namespace spring-boot-app-prod \
  --atomic
```

---

## 6. Verify the Rollback

After **any** rollback, run the full verification sequence:

```bash
# Pods ready?
kubectl get pods -n spring-boot-app-prod -l app.kubernetes.io/name=spring-boot-app
kubectl rollout status deployment/spring-boot-app -n spring-boot-app-prod

# HPA / PDB still healthy?
kubectl get hpa -n spring-boot-app-prod
kubectl get pdb -n spring-boot-app-prod

# Endpoints populated?
kubectl get endpoints -n spring-boot-app-prod spring-boot-app

# App actually serving?
curl -sf https://app.example.com/actuator/health
curl -sf https://app.example.com/actuator/health/liveness
curl -sf https://app.example.com/actuator/health/readiness

# Recent logs clean (no OOM, no exceptions, no HikariCP errors)?
kubectl logs -n spring-boot-app-prod -l app.kubernetes.io/name=spring-boot-app --tail=200
```

Expected: all pods `Running` with `1/1` Ready, health checks return
HTTP 200, logs show normal startup with no stack traces.

---

## 7. Post-Rollback Follow-Up

1. **Stop the bleeding first.** Once the rollback is verified, leave
   the cluster alone. Don't try to "fix forward" in the same window.
2. **Open an incident.** File a post-mortem within 5 business days at
   `docs/postmortems/YYYY-MM-DD-<short-name>.md` using the template in
   `docs/postmortems/TEMPLATE.md`.
3. **Notify stakeholders.** Post in `#incidents` Slack with a one-line
   summary: "Rolled back to revision N. Cause: <one sentence>."
4. **Add a regression test.** Whatever the bug was, add a test that
   would have caught it — `*Test` for unit, `*IT` for integration.
5. **Revert the offending PR.** Either revert the merge commit on
   `main`, or open a forward-fix PR. The forward-fix PR is preferred
   for non-trivial changes; revert is preferred for emergency clean-up.

---

## 8. Common Gotchas

- **Helm rollback doesn't fix a broken ConfigMap.** If the bad deploy
  changed a ConfigMap value, `helm rollback` re-applies the *Helm*
  values from the previous release, but only if the prior release
  still has that key in its values. Externally-edited ConfigMaps
  won't roll back.
- **Image still in use.** A `latest` tag is dangerous for rollback —
  the "previous" image may have been garbage-collected. The pipeline
  pushes by commit SHA for this reason. Never tag production images
  with `latest` only.
- **PDB blocks voluntary disruptions longer than expected.** With
  `minAvailable: 2` and 5 replicas, you can only voluntarily roll one
  pod at a time. This is intentional, but a rollback can take 5-10
  minutes to complete. Don't double-rollback — that just queues more
  disruptions.
- **Helm history capped.** The pipeline runs with
  `--history-max 5` in dev and `--history-max 10` in prod. If you
  need to roll back further than that, use kubectl or re-tag an ECR
  image.

---

## 9. References

- [`docs/MONITORING.md`](MONITORING.md) — Grafana dashboards, alert rules.
- [`docs/SECURITY.md`](SECURITY.md) — Incident response for suspected
  compromise (different procedure, don't roll back before triaging).
- Helm docs: <https://helm.sh/docs/helm/helm_rollback/>
- kubectl rollout: <https://kubernetes.io/docs/concepts/workloads/controllers/deployment/#rolling-back-a-deployment>
