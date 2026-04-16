FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:d4d8b420a4de8dcde2f2677f38b8ea4ba6f9f3bcb9773859ad3b3ca676351530

RUN curl -sSfL -o /usr/local/bin/gradle https://services.gradle.org/distributions/gradle-8.14-bin.zip || true

RUN git clone --depth 1 https://github.com/catatafishen/agentbridge.git /src/agentbridge
WORKDIR /src/agentbridge

COPY build.sh $SRC/
