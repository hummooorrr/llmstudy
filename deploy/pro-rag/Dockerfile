FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY . .

RUN chmod +x mvnw \
    && ./mvnw -Dmaven.repo.local=/build/.m2 -pl pro-rag -am clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=builder /build/pro-rag/target/*.jar /app/app.jar

ENV TZ=Asia/Shanghai
ENV JAVA_OPTS="-Xms512m -Xmx1536m"

EXPOSE 28692

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
