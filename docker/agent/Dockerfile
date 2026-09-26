# Image for agent runs. Add whatever the target project needs to build and
# test (JDK and Clojure CLI are included here for Clojure projects).
FROM node:22-bookworm-slim

RUN apt-get update \
 && apt-get install -y --no-install-recommends git curl ca-certificates openjdk-17-jdk-headless rlwrap \
 && curl -fsSL https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh | bash \
 && rm -rf /var/lib/apt/lists/*

RUN npm install -g @anthropic-ai/claude-code

# The orchestrator passes --user to match the owner of the run directories.
ENV HOME=/tmp
