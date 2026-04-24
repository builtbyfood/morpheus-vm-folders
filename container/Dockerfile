FROM nginx:alpine

# Install Python3 (stdlib only, no pip needed)
RUN apk add --no-cache python3

# Copy app files
COPY nginx.conf /etc/nginx/nginx.conf
COPY html/ /usr/share/nginx/html/vmfolders/
COPY vmfolders-api.py /app/vmfolders-api.py

# Data directory for JSON database
RUN mkdir -p /data && chmod 777 /data

# Startup script - runs both nginx and the Python API
COPY start.sh /start.sh
RUN chmod +x /start.sh

EXPOSE 80

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD wget -qO- http://localhost/vmfolders-api/health || exit 1

CMD ["/start.sh"]
