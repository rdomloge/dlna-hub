# Stage 1: Build frontend
FROM node:20-alpine AS frontend-build
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: Build backend
FROM eclipse-temurin:21-jdk-alpine AS backend-build
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
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
