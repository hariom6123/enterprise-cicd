# spring-boot-app

Java 21 / Spring Boot 3.x reference application wired to the enterprise CI/CD
pipeline (`ci-cd-pipeline.yml`) at the repo root.

## Stack

| Layer        | Choice                                     |
|--------------|--------------------------------------------|
| Runtime      | Java 21 (Temurin)                          |
| Framework    | Spring Boot 3.3.x (web, data-jpa, data-redis, actuator, validation) |
| Build        | Maven (wrapper: `./mvnw`)                 |
| Database     | PostgreSQL (prod), H2 in-memory (`test`)   |
| Cache        | Redis (counter)                            |
| Quality      | JaCoCo, SonarQube, OWASP dependency-check  |

## Endpoints

| Method | Path                              | Purpose                              |
|--------|-----------------------------------|--------------------------------------|
| GET    | `/api/users`                      | List users                           |
| GET    | `/api/users/{id}`                 | Fetch a user                         |
| POST   | `/api/users`                      | Create a user (validates body)       |
| PUT    | `/api/users/{id}`                 | Update a user                        |
| DELETE | `/api/users/{id}`                 | Delete a user                        |
| GET    | `/api/counter`                    | Read Redis counter                   |
| POST   | `/api/counter/increment`          | Bump Redis counter                   |
| POST   | `/api/counter/reset`              | Reset Redis counter to 0             |
| GET    | `/actuator/health`                | Aggregate health (probes + components) |
| GET    | `/actuator/health/liveness`       | Liveness probe                       |
| GET    | `/actuator/health/readiness`      | Readiness probe                      |
| GET    | `/actuator/info`                  | Build / app metadata                 |

Error responses use RFC 7807 `application/problem+json`. Examples:

```json
{
  "type": "https://api.example.com/errors/404",
  "title": "Not Found",
  "status": 404,
  "detail": "User not found: id=99"
}
```

```json
{
  "type": "https://api.example.com/errors/409",
  "title": "Resource conflict",
  "status": 409,
  "detail": "..."
}
```

The pipeline's smoke tests target these actuator paths:

```bash
curl http://<host>/actuator/health/liveness
curl http://<host>/actuator/health/readiness
curl http://<host>/actuator/health
curl http://<host>/actuator/info
```

## Build & test

```bash
# unit tests only (Surefire, matches `*Test`)
./mvnw -B -ntp test

# full verify (Surefire + Failsafe + JaCoCo + OWASP)
./mvnw -B -ntp verify

# build the Spring Boot fat jar the pipeline uploads
./mvnw -B -ntp -DskipTests -Drevision=local clean package
ls -la target/*.jar
```

The pipeline overrides `${revision}` to the short commit SHA:

```bash
mvn -B -V -ntp -DskipTests -Dmaven.javadoc.skip=true -Drevision=${GITHUB_SHA::7} clean package
```

`flatten-maven-plugin` resolves `${revision}` into the final pom version so the
resulting fat jar has a real (non-SNAPSHOT) version baked into its MANIFEST.

## Run locally

```bash
# dev profile (H2 + localhost Redis defaults; you only need Java 21)
./mvnw -B -ntp spring-boot:run
```

```bash
# production profile (Postgres + Redis from env vars, just like the container)
SPRING_PROFILES_ACTIVE=production \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/appdb \
SPRING_DATASOURCE_USERNAME=app \
SPRING_DATASOURCE_PASSWORD=secret \
SPRING_REDIS_HOST=localhost \
SPRING_REDIS_PORT=6379 \
java -jar target/spring-boot-app.jar
```

## Container

The multi-stage `Dockerfile` (at the repo root) builds the runtime image:

1. `eclipse-temurin:21-jdk-jammy` — runs `./mvnw clean package`
2. `eclipse-temurin:21-jre-jammy` — copies the extracted Spring Boot layers,
   runs as `spring:spring` (uid 10001), entrypoint `tini --` + `JarLauncher`

The container's `HEALTHCHECK` hits `/actuator/health/liveness`, which is what
the pipeline's `Smoke Tests` step curls in the dev EKS deploy job.

## Pipeline integration

Place this project at the repo root and the workflow expects exactly:

| Path                                | Used by                                |
|-------------------------------------|----------------------------------------|
| `pom.xml`                           | Maven build, JaCoCo, OWASP, SonarQube  |
| `Dockerfile`                        | Container build (builder + runtime)    |
| `mvnw`, `.mvn/`                     | Wrapper used by Dockerfile builder     |
| `target/*.jar`                      | `spring-boot-jar` artifact + Dockerfile input |
| `target/site/jacoco/jacoco.xml`     | SonarQube coverage                     |
| `target/dependency-check-report.*`  | OWASP report artifact                  |

The Helm chart (`charts/spring-boot-app/`) and Kubernetes manifests
(`k8s/dev`, `k8s/prod`) live in the repo root alongside this project.
