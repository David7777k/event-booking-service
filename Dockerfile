# Build and runtime are separate images. The final one carries a JRE and a jar,
# not a JDK, Maven, and the whole dependency cache.

FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Dependencies are resolved in their own layer, from the build files alone.
# Copying the sources first would invalidate this layer on every code change and
# re-download the world on each build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q --no-transfer-progress dependency:go-offline

COPY src/ src/
# Tests run in CI against a real database. Running them here would need Docker
# inside Docker for Testcontainers, and would make every image build minutes
# longer for a check that has already happened.
RUN ./mvnw -B -q --no-transfer-progress -DskipTests package


FROM eclipse-temurin:21-jre-alpine AS runtime

# A non-root user: a process that never needs to write outside its own
# directory should not be able to.
RUN addgroup -S seatflow && adduser -S seatflow -G seatflow

WORKDIR /app
COPY --from=build --chown=seatflow:seatflow /build/target/*.jar app.jar

USER seatflow
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes its heap from
# the container limit, so changing the limit does not silently leave the heap
# at whatever was hard-coded here.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
