# Build the jars first:  mvn -q clean package -DskipTests
# Then:                  docker compose build
FROM eclipse-temurin:21-jre
ARG MODULE
WORKDIR /app
COPY ${MODULE}/target/${MODULE}.jar app.jar
# Container-aware heap sizing; virtual threads enabled via spring.threads.virtual.enabled
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational"
EXPOSE 8080 8081 8082 8083
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
