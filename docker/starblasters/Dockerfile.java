FROM ghcr.io/dbbaskette/starblaster:base

USER root

RUN apt-get update && apt-get install -y wget unzip && \
    wget -qO- https://download.oracle.com/java/21/latest/jdk-21_linux-x64_bin.tar.gz | tar xz -C /opt && \
    ln -s /opt/jdk-21* /opt/jdk && \
    apt-get clean && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/opt/jdk PATH="/opt/jdk/bin:${PATH}"

RUN curl -fsSL https://dlcdn.apache.org/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz | tar xz -C /opt && \
    ln -s /opt/apache-maven-* /opt/maven
ENV PATH="/opt/maven/bin:${PATH}"

RUN curl -fsSL https://services.gradle.org/distributions/gradle-8.12-bin.zip -o /tmp/gradle.zip && \
    unzip -q /tmp/gradle.zip -d /opt && ln -s /opt/gradle-* /opt/gradle && rm /tmp/gradle.zip
ENV PATH="/opt/gradle/bin:${PATH}"

USER centurion
