# Docker Official Images on ECR Public avoid Docker Hub's anonymous pull quota in CI.
FROM public.ecr.aws/docker/library/maven:3.9.16-eclipse-temurin-11 AS builder

WORKDIR /src
# dependencies are cached in their own layer while the sources change
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
# tests and linters run in CI and `make check`, the image build only packages
RUN mvn -B -ntp -DskipTests package

# The Java runtime of the hub: the modules of the jar (jdeps) and the ones it loads by reflection or as services (TLS
# with EC keys and the zip file system, JMX, sun.misc.Unsafe, the JNDI of Jetty, the HTTP client), not the whole JRE
RUN jdeps --ignore-missing-deps --multi-release 11 --print-module-deps \
      target/mcloud-grid-jar-with-dependencies.jar > /tmp/modules && \
    jlink --add-modules "$(cat /tmp/modules),jdk.crypto.ec,jdk.zipfs,java.management,jdk.management,jdk.unsupported,java.naming,java.net.http,jdk.localedata" \
      --strip-debug --no-man-pages --no-header-files --compress=2 --output /opt/jre && \
    /opt/jre/bin/java -version


# The Ubuntu of the eclipse-temurin images with the Java runtime of the builder (Temurin 11, its own time zones and CA
# certificates): curl of the healthcheck, pgrep; the UTF-8 locale of Ubuntu; the security fixes of Ubuntu
FROM public.ecr.aws/docker/library/ubuntu:26.04@sha256:6a06111c3b0a8600feadb270aec771dc239bf407b25625f93f44ab00e11f4d3c

ENV JAVA_HOME=/opt/java/openjdk \
    LANG=C.UTF-8
ENV PATH="${JAVA_HOME}/bin:${PATH}"

RUN export DEBIAN_FRONTEND=noninteractive && \
    apt-get update && \
    apt-get upgrade -y && \
    apt-get install -y --no-install-recommends \
        curl=8.18.0-1ubuntu2.8 ca-certificates=20260816ubuntu0.26.04.1 procps=2:4.0.4-9ubuntu1 && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /opt/jre /opt/java/openjdk

EXPOSE 4444

ENV \
    # STF integration
    STF_URL="" \
    STF_TOKEN="" \
    STF_TIMEOUT=3600 \
    CHECK_APPIUM_STATUS=false \
    # Grid settings
    # As a boolean, maps to "throwOnCapabilityNotPresent"
    GRID_THROW_ON_CAPABILITY_NOT_PRESENT=true \
    # As an integer
    GRID_JETTY_MAX_THREADS=-1 \
    # GRID_NEW_SESSION_WAIT_TIMEOUT and GRID_CLEAN_UP_CYCLE in milliseconds, GRID_BROWSER_TIMEOUT and GRID_TIMEOUT in seconds
    GRID_NEW_SESSION_WAIT_TIMEOUT=600000 \
    GRID_CLEAN_UP_CYCLE=5000 \
    GRID_BROWSER_TIMEOUT=900 \
    GRID_TIMEOUT=150 \
    # Debug
    GRID_DEBUG=false \
    # Proxy
    GRID_PROXY=com.zebrunner.mcloud.grid.MobileRemoteProxy \
    # Capability matcher
    GRID_CAPABILITY_MATCHER=com.zebrunner.mcloud.grid.MobileCapabilityMatcher \
    # JVM heap of the hub; other JVM options can be added with JAVA_OPTS
    JAVA_HEAP_OPTS="-Xms1G -Xmx4G"

# the hub writes its config.json on start, so /opt/selenium belongs to the unprivileged 'ubuntu' user of the base image
# (COPY --chown: a chown -R of its own layer would copy the jar again)
COPY --from=builder --chown=1000:1000 /src/target/mcloud-grid-jar-with-dependencies.jar /opt/selenium/
COPY generate_config entrypoint.sh /opt/bin/
COPY --chown=1000:1000 logger.properties /opt/selenium/
USER 1000:1000

ARG VERSION=dev
LABEL authors=Zebrunner \
      org.opencontainers.image.title="Zebrunner MCloud Grid" \
      org.opencontainers.image.description="The Selenium 3 hub of the Appium nodes of Zebrunner MCloud" \
      org.opencontainers.image.source="https://github.com/zebrunner/mcloud-grid" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}"

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["curl", "-sf", "-o", "/dev/null", "http://localhost:4444/wd/hub/status"]

CMD ["/opt/bin/entrypoint.sh"]
