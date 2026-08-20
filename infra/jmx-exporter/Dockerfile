# Extends the existing kafka-connect image (see infra/docker-compose.yml)
# rather than mounting a downloaded jar as a bind volume - keeps the
# exporter reproducible via `docker compose build`, no binary committed
# to git.
FROM debezium/connect:2.7.3.Final

# The base image's own Dockerfile already switched USER to kafka
# (uid 1001) before this point, so ADD/COPY here inherit that non-root
# user. Two problems that both surface as the same misleading JVM error
# ("Error opening zip file or JAR manifest missing", not a permission
# error):
#   1. A plain ADD from a URL defaults to root:root 600 - unreadable by
#      the kafka user.
#   2. ADD/COPY --chmod=644 fixes the FILE mode, but BuildKit applies
#      that same mode to the parent directory it implicitly creates too
#      (/opt/jmx-exporter ends up drw-r--r--, no execute/search bit for
#      anyone) - so even a correctly-readable file inside becomes
#      unreachable, since traversing a directory needs its execute bit.
# Fixed by doing the copy as root (where permissions don't matter) and
# fixing the directory mode explicitly, then restoring the original user
# so the entrypoint still runs unprivileged as intended.
USER root
ADD https://github.com/prometheus/jmx_exporter/releases/download/1.6.0/jmx_prometheus_javaagent-1.6.0.jar /opt/jmx-exporter/jmx_prometheus_javaagent.jar
COPY config.yaml /opt/jmx-exporter/config.yaml
RUN chmod 755 /opt/jmx-exporter && chmod 644 /opt/jmx-exporter/jmx_prometheus_javaagent.jar /opt/jmx-exporter/config.yaml
USER kafka
