# Docker Official Images on ECR Public avoid Docker Hub's anonymous pull quota in CI.
FROM public.ecr.aws/docker/library/maven:3.9.16-eclipse-temurin-11 AS builder

WORKDIR /src
# dependencies are cached in their own layer while the sources change
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
# tests and linters run in CI and `make check`, the image build only packages
RUN mvn -B -ntp -DskipTests package


FROM public.ecr.aws/docker/library/eclipse-temurin:11.0.32.1_1-jre-resolute@sha256:31ef746477f9ed27c0170585e81874f25f64be591b8c2f0bba18700d0086cbfb

# Apply published Ubuntu security fixes while keeping the same JRE 11.
RUN apt-get update && \
    apt-get install -y --no-install-recommends --only-upgrade \
        libfreetype6=2.14.2+dfsg-1ubuntu0.2 \
        libpng16-16t64=1.6.57-1ubuntu0.1 && \
    rm -rf /var/lib/apt/lists/*

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
    # Timeouts in milliseconds
    GRID_NEW_SESSION_WAIT_TIMEOUT=600000 \
    GRID_CLEAN_UP_CYCLE=5000 \
    GRID_BROWSER_TIMEOUT=0 \
    GRID_TIMEOUT=150 \
    # Debug
    GRID_DEBUG=false \
    # Proxy
    GRID_PROXY=com.zebrunner.mcloud.grid.MobileRemoteProxy \
    # Capability matcher
    GRID_CAPABILITY_MATCHER=com.zebrunner.mcloud.grid.MobileCapabilityMatcher \
    # JVM heap of the hub; other JVM options can be added with JAVA_OPTS
    JAVA_HEAP_OPTS="-Xms1G -Xmx4G"

COPY --from=builder /src/target/mcloud-grid-jar-with-dependencies.jar /opt/selenium/
COPY generate_config entrypoint.sh /opt/bin/
COPY logger.properties /opt/selenium/

# the hub writes its config.json on start, so /opt/selenium belongs to the unprivileged 'ubuntu' user of the base image
RUN chown -R 1000:1000 /opt/selenium
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
