#!/bin/sh
# Start the Python companion API in background
python3 /app/vmfolders-api.py &
# Start nginx in foreground
nginx -g "daemon off;"
