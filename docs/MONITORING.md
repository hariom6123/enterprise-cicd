# Monitoring Strategy

Three-pillar observability for the Spring Boot application: **metrics**,
**logs**, and **traces**. Aligned with the SLOs in
[`docs/ARCHITECTURE.md` §8](ARCHITECTURE.md).

---

## 1. Metrics (Prometheus + Grafana)

The Helm chart ships a `ServiceMonitor` that the cluster Prometheus
operator picks up automatically. The Spring Boot Actuator endpoint
`/actuator/prometheus` is exposed on the `management` port (8080) — see
`charts/spring-boot-app/values.yaml` → `serviceMonitor`.

### Scrape Configuration

The chart's `ServiceMonitor` is equivalent to:

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: spring-boot-app
spec:
  selector:
    matchLabels:
      app.kubernetes.io/name: spring-boot-app
  endpoints:
    - port: management
      path: /actuator/prometheus
      interval: 30s
      scrapeTimeout: 10s
```

### Required Maven Dependency (already in `pom.xml`)

`micrometer-registry-prometheus` is exposed transitively by
`spring-boot-starter-actuator`. Verify with:

```bash
./mvnw -B -ntp dependency:tree | grep micrometer-registry-prometheus
```

### Custom Business Metrics

Add counters/timers under `com.example.springboot.*` for anything
worth alerting on that HTTP metrics can't capture. Example:

```java
Counter.builder("user.create.attempts")
    .tag("source", "api")
    .register(meterRegistry)
    .increment();
```

---

## 2. The Four Golden Signals

| Signal | PromQL | Use |
|--------|--------|-----|
| **Request rate** | `sum(rate(http_server_requests_seconds_count[5m]))` | Traffic dashboard |
| **Error rate** | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))` | SLO compliance |
| **Latency (P99)** | `histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))` | Performance dashboard |
| **Saturation** | `sum(rate(jvm_memory_used_bytes[5m])) by (area)` and `sum(rate(process_cpu_usage[5m]))` | Capacity planning |

### Grafana Dashboard Skeleton

A reasonable first dashboard uses these panels, in this order:

1. **Request rate** by `uri` (timeseries, 5m window)
2. **Error rate** % (stat panel, red threshold at 1%)
3. **Latency P50/P95/P99** (timeseries, by `uri`)
4. **JVM heap used / max** (timeseries, 1h window)
5. **HikariCP active connections** vs `maximum-pool-size` (gauge)
6. **HTTP requests in flight** (gauge)

Export the dashboard JSON to `dashboards/spring-boot-app.json` once
stable so it can be replayed into new clusters.

---

## 3. Logs (Fluent Bit → CloudWatch / OpenSearch)

Structured JSON logging only. Configure the application to emit
JSON-formatted logs so Fluent Bit can parse them without regex.

### `logback-spring.xml`

Add to `java-app/src/main/resources/`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

(`logstash-logback-encoder` is added to `pom.xml` as a runtime dep.)

### Correlation IDs

Set `traceId` / `spanId` in MDC from a `WebMvcConfigurer` filter, or
let Spring Boot's `micrometer-tracing-bridge-otel` populate them
automatically once OpenTelemetry is on the classpath.

### Per-Namespace Streams

```hcl
resource "aws_cloudwatch_log_group" "app" {
  name              = "/aws/eks/spring-boot-app-prod"
  retention_in_days = 30
}
```

---

## 4. Traces (OpenTelemetry → X-Ray / Jaeger / Tempo)

Distributed tracing is **optional** in dev, **required** in prod.

### Add to `pom.xml`

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

### Configure Endpoint

```yaml
management:
  tracing:
    sampling:
      probability: 0.1  # 10% in prod, 1.0 in dev
  otlp:
    tracing:
      endpoint: http://tempo-distributor.observability.svc:4318
```

### Sampling

10% in prod is the right starting point. Increase to 100% during
incident investigation. Don't run 100% indefinitely — cardinality
explodes storage cost.

---

## 5. SLOs and Error Budgets

| SLI | SLO Target | 30-day Error Budget |
|-----|------------|---------------------|
| Availability (`/actuator/health` returns 2xx) | 99.95% | 21.6 minutes |
| P99 latency on `/api/users` | < 500 ms | — |
| Error rate (5xx) on any `/api/*` | < 0.1% | — |
| Throughput | > 1000 RPS sustained | — |

### Burn-Rate Alert (multi-window)

```yaml
- alert: SLOBurnRateFast
  expr: |
    (
      sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
      /
      sum(rate(http_server_requests_seconds_count[5m]))
    ) > (14.4 * 0.0005)
  for: 2m
  labels:
    severity: critical
  annotations:
    summary: "SLO error budget burning 14.4x too fast (5m window)"
    runbook: "https://wiki.example.com/runbooks/slo-burn"
```

The `14.4` multiplier corresponds to burning the full 30-day budget
in 2 days — fast enough to alert on, slow enough to avoid paging on a
single bad request.

---

## 6. Alerting Rules

```yaml
groups:
  - name: spring-boot-app.rules
    rules:
      - alert: HighErrorRate
        expr: |
          (
            sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
            /
            sum(rate(http_server_requests_seconds_count[5m]))
          ) > 0.05
        for: 5m
        labels: {severity: critical}
        annotations:
          summary: "HTTP 5xx > 5% for 5m"
          runbook: "https://wiki.example.com/runbooks/5xx"

      - alert: HighP99Latency
        expr: |
          histogram_quantile(0.99,
            sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m]))
          ) > 0.5
        for: 10m
        labels: {severity: warning}
        annotations:
          summary: "P99 latency > 500ms on {{ $labels.uri }}"

      - alert: PodCrashLooping
        expr: |
          rate(kube_pod_container_status_restarts_total{namespace="spring-boot-app-prod"}[10m]) > 0.1
        for: 10m
        labels: {severity: critical}
        annotations:
          summary: "Pod {{ $labels.pod }} restarting > 1/100s"

      - alert: HikariPoolExhaustion
        expr: |
          hikaricp_connections_active / hikaricp_connections_max > 0.9
        for: 5m
        labels: {severity: warning}
        annotations:
          summary: "HikariCP > 90% utilized on {{ $labels.instance }}"
```

---

## 7. On-Call Playbook (one-pager)

1. **Page arrives** → check Grafana dashboard, identify the signal.
2. **Mitigate first, diagnose second.** Roll back if a deploy < 30 min
   old and error rate is climbing. See [`docs/ROLLBACK.md`](ROLLBACK.md).
3. **Check recent deploys**: `kubectl rollout history deployment/spring-boot-app -n spring-boot-app-prod`.
4. **Check pod health**: `kubectl get pods -n spring-boot-app-prod -l app.kubernetes.io/name=spring-boot-app`.
5. **Tail logs**: `kubectl logs -n spring-boot-app-prod -l app.kubernetes.io/name=spring-boot-app --tail=200 -f`.
6. **Trace a slow request**: pull the `traceId` from the log line,
   open in Tempo/Jaeger.
7. **Resolve and post-mortem** within 5 business days. File the
   post-mortem in `docs/postmortems/YYYY-MM-DD-<short-name>.md`.
