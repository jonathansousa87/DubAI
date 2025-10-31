FROM python:3.8-slim

RUN apt-get update && apt-get install -y \
    ffmpeg \
    libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

RUN pip install spleeter

WORKDIR /workdir

RUN spleeter separate -p spleeter:2stems-16kHz /dev/null || true
RUN spleeter separate -p spleeter:4stems-16kHz /dev/null || true
RUN spleeter separate -p spleeter:5stems-16kHz /dev/null || true

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

CMD ["tail", "-f", "/dev/null"]
