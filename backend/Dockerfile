FROM python:3.11-slim

COPY --from=ghcr.io/astral-sh/uv:0.12.21 /uv /usr/local/bin/uv

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    PYTHONUNBUFFERED=1

COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY app ./app
COPY docker-entrypoint.sh /usr/local/bin/

ENV PATH=/app/.venv/bin:$PATH \
    COSTCO_DATA_DIR=/data \
    PUID=99 \
    PGID=100
VOLUME /data
EXPOSE 8747

HEALTHCHECK --interval=60s --timeout=5s --start-period=20s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8747/api/health')"

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8747", "--proxy-headers", "--forwarded-allow-ips=*"]
