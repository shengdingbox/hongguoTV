ARG RUNNER_IMAGE=ghcr.io/actions/actions-runner@sha256:6d0b2d80d80f143f77dcb676e50920100bbb547e39e883454d57f45ab8c31300
FROM ${RUNNER_IMAGE}

USER root
RUN sed -i \
      -e "s|http://archive.ubuntu.com/ubuntu|https://mirrors.nju.edu.cn/ubuntu|g" \
      -e "s|http://security.ubuntu.com/ubuntu|https://mirrors.nju.edu.cn/ubuntu|g" \
      /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git python3 \
    && rm -rf /var/lib/apt/lists/*

COPY start.sh /opt/hongguotv-release-runner/start.sh
USER runner
ENTRYPOINT ["/bin/bash", "/opt/hongguotv-release-runner/start.sh"]
