# syntax=docker/dockerfile:1
# One image recipe for every JVM module: docker build --build-arg MODULE=services/order-service .
# eclipse-temurin publishes amd64 and arm64 variants, so the same file builds for x86 laptops and Arm servers.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -q -B -pl "${MODULE}" -am package -DskipTests \
 && cp "${MODULE}"/target/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
COPY --from=build /app.jar /app/app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
