FROM docker.io/eclipse-temurin:21-jre-alpine

LABEL maintainer="PaymentBanregio Team"
LABEL description="Procesador de pagos de Banregio - Windows Server + Podman"
LABEL version="1.0.0"

ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080 \
    JAVA_OPTS="-Xmx1024m -Xms512m -XX:+UseG1GC -XX:+UseContainerSupport" \
    TZ=America/Mexico_Cit

RUN apk add --no-cache \
    curl \
    tzdata \
    && cp /usr/share/zoneinfo/America/Mexico_City /etc/localtime \
    && echo "America/Mexico_City" > /etc/timezone \
    && apk del tzdata

RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

RUN mkdir -p /app/logs && \
    chown -R appuser:appgroup /app

COPY target/PaymentBanregio-*.jar app.jar

RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=15s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "echo 'Iniciando PaymentBanregio con Podman...' && exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]