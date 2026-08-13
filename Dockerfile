# Stage 1: Build frontend
FROM --platform=$BUILDPLATFORM node:20-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: Build backend
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21-alpine AS backend-build
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn dependency:go-offline -B
COPY backend/ ./
RUN mvn package -DskipTests -B

# Stage 3: Production
FROM eclipse-temurin:21-jre-alpine AS production
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
COPY --from=backend-build /app/backend/target/*.jar /app/app.jar
COPY --from=frontend-build /app/frontend/dist /app/static
EXPOSE 9100
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
