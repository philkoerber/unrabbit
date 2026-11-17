FROM node:22-bookworm-slim AS codex-install
ARG CODEX_VERSION=0.165.0
RUN npm install --global "@openai/codex@${CODEX_VERSION}"

FROM ubuntu:24.04 AS base
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 python3-venv ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home --shell /bin/sh unrabbit
RUN mkdir /data /opt/unrabbit-model /opt/unrabbit-voices && chown 10001:10001 /data /opt/unrabbit-model /opt/unrabbit-voices
COPY requirements-speech.txt /tmp/requirements-speech.txt
RUN python3 -m venv /opt/unrabbit-runtime \
    && /opt/unrabbit-runtime/bin/pip install --no-cache-dir -r /tmp/requirements-speech.txt \
    && rm /tmp/requirements-speech.txt
ENV PATH="/opt/unrabbit-runtime/bin:${PATH}" UNRABBIT_SPEECH_MODEL=/opt/unrabbit-model UNRABBIT_TTS_MODELS=/opt/unrabbit-voices HF_HUB_DISABLE_XET=1
WORKDIR /app
COPY unrabbit_vm ./unrabbit_vm
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN python3 -m unrabbit_vm speech-setup
RUN python3 -m unrabbit_vm tts-setup
ENTRYPOINT ["python3", "-m", "unrabbit_vm"]
CMD ["--state", "/data", "--listen", "0.0.0.0"]

# Optional interactive setup image; keep QR dependencies out of the worker image.
FROM base AS setup
USER root
COPY requirements-setup.txt /tmp/requirements-setup.txt
RUN /opt/unrabbit-runtime/bin/pip install --no-cache-dir -r /tmp/requirements-setup.txt \
    && rm /tmp/requirements-setup.txt
USER 10001:10001
ENTRYPOINT ["python3", "-m", "unrabbit_vm", "setup"]
CMD []

# Opt-in development image. Authentication happens later, in a private volume.
FROM setup AS codex
USER root
COPY --from=codex-install /usr/local/bin/node /usr/local/bin/node
COPY --from=codex-install /usr/local/lib/node_modules/@openai/codex /opt/codex
RUN ln -s /opt/codex/bin/codex.js /usr/local/bin/codex \
    && install -d -o 10001 -g 10001 -m 0700 /home/unrabbit/.codex /home/unrabbit/workspace
ENV CODEX_HOME=/home/unrabbit/.codex
USER 10001:10001

FROM base AS runtime
