FROM maven:3.9.16-eclipse-temurin-11 AS builder

WORKDIR /src
# dependencies are cached in their own layer while the sources change
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
# tests and linters run in CI and `make check`, the image build only packages
RUN mvn -B -ntp -o -DskipTests package assembly:single


FROM eclipse-temurin:11.0.32.1_1-jre
LABEL authors=Zebrunner

EXPOSE 4444

# STF integration
ENV STF_URL=""
ENV STF_TOKEN=""
ENV STF_TIMEOUT=3600
ENV CHECK_APPIUM_STATUS=false

# Grid settings
# As a boolean, maps to "throwOnCapabilityNotPresent"
ENV GRID_THROW_ON_CAPABILITY_NOT_PRESENT=true
# As an integer
ENV GRID_JETTY_MAX_THREADS=-1
# Timeouts in milliseconds
ENV GRID_NEW_SESSION_WAIT_TIMEOUT=600000
ENV GRID_CLEAN_UP_CYCLE=5000
ENV GRID_BROWSER_TIMEOUT=0
ENV GRID_TIMEOUT=150
# Debug
ENV GRID_DEBUG=false
# Proxy
ENV GRID_PROXY=com.zebrunner.mcloud.grid.MobileRemoteProxy
# Capability matcher
ENV GRID_CAPABILITY_MATCHER=com.zebrunner.mcloud.grid.MobileCapabilityMatcher

# JVM heap of the hub; other JVM options can be added with JAVA_OPTS
ENV JAVA_HEAP_OPTS="-Xms1G -Xmx4G"

COPY --from=builder /src/target/mcloud-grid-jar-with-dependencies.jar /opt/selenium/
COPY generate_config entrypoint.sh /opt/bin/
COPY logger.properties /opt/selenium/

# the hub writes its config.json on start, so /opt/selenium belongs to the unprivileged 'ubuntu' user of the base image
RUN chown -R 1000:1000 /opt/selenium
USER 1000:1000

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["curl", "-sf", "-o", "/dev/null", "http://localhost:4444/wd/hub/status"]

CMD ["/opt/bin/entrypoint.sh"]
