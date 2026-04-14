FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=Asia/Tokyo
ENV LANG=ja_JP.UTF-8
ENV LC_ALL=ja_JP.UTF-8

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_USER_HOME=/root/.android
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

RUN apt-get update && apt-get install -y \
    locales tzdata ca-certificates curl wget unzip zip xz-utils \
    git git-lfs sudo bash-completion \
    openjdk-21-jdk \
    adb usbutils \
    python3 python3-pip \
    vim nano less procps psmisc \
    dbus-x11 terminator \
 && locale-gen ja_JP.UTF-8 \
 && update-ca-certificates \
 && rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools

RUN cd /tmp \
 && wget -O commandlinetools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
 && unzip commandlinetools.zip -d /tmp/cmdline-tools \
 && mv /tmp/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest \
 && rm -rf /tmp/commandlinetools.zip /tmp/cmdline-tools

RUN yes | sdkmanager --licenses

RUN sdkmanager \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "cmdline-tools;latest"

WORKDIR /workspace
RUN echo "export PS1='\[\e[1;36m\]\u@\h\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\$ '" >> /root/.bashrc
CMD ["sleep", "infinity"]