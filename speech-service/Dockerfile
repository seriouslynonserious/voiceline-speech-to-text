FROM python:3.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 MODEL_CACHE=/models HF_HOME=/models/huggingface XDG_CACHE_HOME=/models/cache
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
RUN python -m pytest -q && useradd --system --uid 10001 app && mkdir /models && chown app /models
USER app
EXPOSE 8000
CMD ["uvicorn","app:app","--host","0.0.0.0","--port","8000","--ws-max-size","8192","--ws-max-queue","16"]
