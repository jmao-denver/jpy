# Linux test image for the FFM jpy: the official python image (its python links a shared libpython,
# which Python-first needs) plus Temurin JDK 25 and numpy. Mount the repository at /src and run
# `python ffm/ci.py`. See ffm/docker/run.sh.
ARG PYTHON_VERSION=3.12
FROM docker.io/library/eclipse-temurin:25-jdk AS jdk
FROM docker.io/library/python:${PYTHON_VERSION}
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
RUN pip install --no-cache-dir --root-user-action=ignore numpy
WORKDIR /src
