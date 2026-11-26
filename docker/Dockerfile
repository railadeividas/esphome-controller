FROM ghcr.io/cirruslabs/android-sdk:35
ARG GRADLE_VERSION=8.9
RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && rm /tmp/gradle.zip
ENV PATH="/opt/gradle-8.9/bin:${PATH}"
WORKDIR /workspace
