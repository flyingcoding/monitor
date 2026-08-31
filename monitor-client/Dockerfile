FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY target/monitor-client.jar app.jar


ENTRYPOINT ["java", "-Xmx64m", "-Xms32m", "-XX:MaxDirectMemorySize=16m", "-XX:MaxMetaspaceSize=64m", "-XX:+ExitOnOutOfMemoryError", "-XX:ErrorFile=/app/logs/hs_err_pid.log", "-jar", "app.jar"]
