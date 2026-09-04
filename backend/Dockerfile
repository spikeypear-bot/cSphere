# ---- Stage 1: resolve dependencies -------------------------------------------
# Kept separate from the source copy so a code-only change reuses this layer
# instead of re-downloading the whole dependency tree.
FROM eclipse-temurin:25-jdk AS deps
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline

# ---- Stage 2: compile and package --------------------------------------------
FROM deps AS build
WORKDIR /build

COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests clean package

# Split the fat jar into Spring Boot's layers so the runtime image puts rarely
# changing dependencies below frequently changing application classes.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# ---- Stage 3: runtime --------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime
WORKDIR /app

RUN addgroup -S spring && adduser -S -G spring spring
USER spring:spring

# Ordered least- to most-frequently changing.
COPY --from=build --chown=spring:spring /build/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /build/extracted/application/ ./

EXPOSE 8080

# MaxRAMPercentage sizes the heap from the container's memory limit rather than
# the host's total RAM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
