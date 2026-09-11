#!/bin/sh
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
apk add --no-cache \
  build-base \
  meson \
  ninja \
  python3 \
  py3-mako \
  py3-yaml \
  bison \
  flex \
  libdrm-dev \
  wayland-dev \
  wayland-protocols \
  libx11-dev \
  libxext-dev \
  libxcb-dev \
  xcb-util-dev \
  libxshmfence-dev \
  libxrandr-dev \
  zlib-dev \
  zstd-dev \
  expat-dev \
  curl \
  tar \
  xz
echo "Deps install exit code: $?"
