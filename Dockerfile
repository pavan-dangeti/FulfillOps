# syntax=docker/dockerfile:1
# One image recipe for every JVM module: docker build --build-arg MODULE=services/order-service .
# eclipse-temurin publishes amd64 and arm64 variants, so the same file builds for x86 laptops and Arm servers.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ARG MODULE
# The cache mount covers the dependency repository only, not all of ~/.m2.
#
# The Maven wrapper downloads its own distribution into ~/.m2/wrapper/dists and then moves it into
# place. With the whole of ~/.m2 shared, the four service images that `docker compose up --build`
# builds in parallel raced on that move: one container removed the target directory while another was
# writing into it, and the build died with "inter-device move failed ... Directory not empty" and
# "mvn: not found". It is intermittent, and it took three green runs before it appeared.
#
# Mounting just the repository keeps the expensive part of the cache — downloaded artifacts — while
# leaving each build's wrapper distribution to itself.
RUN --mount=type=cache,target=/root/.m2/repository \
    ./mvnw -q -B -pl "${MODULE}" -am package -DskipTests \
 && cp "${MODULE}"/target/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
COPY --from=build /app.jar /app/app.jar
USER app
# Measured, not defaulted (docs/performance.md, "Memory"). glibc gives each thread that allocates
# its own arena, which showed up as ~100-140 MB of native memory the JVM does not account for; two
# arenas is plenty for a service this size. The heap is a third of the container because class
# metadata, code cache and native memory take most of the rest — at 75%, a heap that actually grew
# to its maximum could not fit beside them.
ENV MALLOC_ARENA_MAX=2
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=33", "-jar", "/app/app.jar"]
