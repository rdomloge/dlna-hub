# Stage 1: Build backend
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY backend/pom.xml ./
RUN mvn dependency:go-offline -B
COPY backend/ ./
RUN mvn package -DskipTests -B

# Stage 2: Production
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
COPY --from=build /app/target/*.jar /app/app.jar
EXPOSE 9200
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
