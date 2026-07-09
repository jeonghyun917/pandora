# syntax=docker/dockerfile:1

FROM node:24-alpine AS frontend-build
WORKDIR /workspace/frontend

COPY frontend/package*.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src
COPY --from=frontend-build /workspace/frontend/dist ./src/main/resources/static

RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV JAVA_OPTS=""

COPY --from=backend-build /workspace/target/pandora-0.0.1-SNAPSHOT.jar /app/pandora.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/pandora.jar"]
