# Stage 1: Compile Java sources
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY src ./src
COPY lib ./lib

RUN mkdir -p out && \
    find src -name "*.java" > sources.txt && \
    javac -cp "src:lib/*" -d out @sources.txt

# Stage 2: Minimal runtime with JRE + Nginx reverse proxy on a single port
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache nginx && \
    mkdir -p /run/nginx /app/data

COPY --from=builder /app/out ./out
COPY --from=builder /app/lib ./lib
COPY nginx.conf /etc/nginx/nginx.conf
COPY entrypoint.sh /app/entrypoint.sh
RUN sed -i 's/\r$//' /app/entrypoint.sh && chmod +x /app/entrypoint.sh

ENV DB_PATH=/app/data/epigaea.db
VOLUME ["/app/data"]

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]
