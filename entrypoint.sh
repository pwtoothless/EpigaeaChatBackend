#!/bin/sh
set -e

mkdir -p /run/nginx /app/data

# Start Java backend in background
java -cp "out:lib/*" Main &
JAVA_PID=$!

# Start Nginx in foreground
nginx -g "daemon off;" &
NGINX_PID=$!

# Forward signals and exit if either process stops
trap "kill $JAVA_PID $NGINX_PID 2>/dev/null || true" INT TERM

while kill -0 "$JAVA_PID" 2>/dev/null && kill -0 "$NGINX_PID" 2>/dev/null; do
    sleep 1
done

kill "$JAVA_PID" "$NGINX_PID" 2>/dev/null || true
exit 1
