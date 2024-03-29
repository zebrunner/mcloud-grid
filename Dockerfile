FROM openjdk:11
LABEL authors=Zebrunner

EXPOSE 4444

# STF integration
ENV STF_URL ""
ENV STF_TOKEN ""
ENV STF_TIMEOUT 3600
ENV CHECK_APPIUM_STATUS false

# Grid settings
# As a boolean, maps to "throwOnCapabilityNotPresent"
ENV GRID_THROW_ON_CAPABILITY_NOT_PRESENT true
# As an integer
ENV GRID_JETTY_MAX_THREADS -1
# Timeouts in milliseconds
ENV GRID_NEW_SESSION_WAIT_TIMEOUT 600000
ENV GRID_CLEAN_UP_CYCLE 5000
ENV GRID_BROWSER_TIMEOUT 0
ENV GRID_TIMEOUT 150
# Debug
ENV GRID_DEBUG false
# Proxy
ENV GRID_PROXY com.zebrunner.mcloud.grid.MobileRemoteProxy
# Capability matcher
ENV GRID_CAPABILITY_MATCHER com.zebrunner.mcloud.grid.MobileCapabilityMatcher

RUN mkdir /opt/selenium

COPY generate_config \
    entry_point.sh \
    /opt/bin/
COPY target/mcloud-grid-jar-with-dependencies.jar \
    /opt/selenium
COPY target/mcloud-grid-1.0.jar \
    /opt/selenium
COPY logger.properties \
    /opt/selenium
# Running this command as sudo just to avoid the message:
# To run a command as administrator (user "root"), use "sudo <command>". See "man sudo_root" for details.
# When logging into the container
RUN /opt/bin/generate_config > /opt/selenium/config.json

CMD ["/opt/bin/entry_point.sh"]
