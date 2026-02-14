FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY target/monitor-client.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Xmx64m", "-Xms32m", "-jar", "app.jar"]
