FROM ghcr.io/actions/actions-runner:latest

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git python3 \
    && rm -rf /var/lib/apt/lists/*

COPY start.sh /opt/hongguotv-release-runner/start.sh
USER runner
ENTRYPOINT ["/bin/bash", "/opt/hongguotv-release-runner/start.sh"]
