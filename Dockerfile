# DevBrain backend: Spring Boot API + the CLI tools its parsers shell out to
# (yt-dlp and ffmpeg for video, whisper.cpp for speech-to-text when a video has no captions).

# ---- build the jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && cp target/devbrain-*.jar /app/app.jar

# ---- whisper.cpp + a speech model (same distro as the runtime image) ----
FROM ubuntu:24.04 AS whisper
ARG WHISPER_VERSION=v1.7.6
ARG WHISPER_MODEL=base.en
RUN apt-get update && apt-get install -y --no-install-recommends git cmake build-essential ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN git clone --depth 1 --branch ${WHISPER_VERSION} https://github.com/ggml-org/whisper.cpp /whisper \
    && cmake -S /whisper -B /whisper/build -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF \
       -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF -DWHISPER_BUILD_TESTS=OFF \
    && cmake --build /whisper/build --target whisper-cli -j"$(nproc)"
RUN curl -fsSL -o /ggml-model.bin "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-${WHISPER_MODEL}.bin"

# ---- runtime ----
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg python3-pip curl \
    && pip3 install --no-cache-dir --break-system-packages yt-dlp \
    && rm -rf /var/lib/apt/lists/*
COPY --from=whisper /whisper/build/bin/whisper-cli /usr/local/bin/whisper-cli
COPY --from=whisper /ggml-model.bin /opt/whisper/ggml-model.bin
COPY --from=build /app/app.jar /app/app.jar

RUN useradd --system --create-home devbrain
USER devbrain
ENV WHISPER_MODEL_PATH=/opt/whisper/ggml-model.bin
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=10 \
    CMD curl -fs http://localhost:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
