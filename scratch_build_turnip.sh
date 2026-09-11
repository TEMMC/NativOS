#!/bin/sh
set -e
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
mkdir -p /tmp/mesa-build
cd /tmp/mesa-build

if [ ! -f mesa-24.2.8.tar.xz ]; then
  if [ -f /data/local/tmp/mesa-24.2.8.tar.xz ]; then
    echo "Copying Mesa archive from /data/local/tmp..."
    cp /data/local/tmp/mesa-24.2.8.tar.xz ./
  else
    echo "Downloading Mesa 24.2.8 source..."
    curl -L -o mesa-24.2.8.tar.xz https://archive.mesa3d.org/mesa-24.2.8.tar.xz
  fi
fi

if [ ! -d mesa-24.2.8 ]; then
  echo "Extracting Mesa 24.2.8..."
  tar -xf mesa-24.2.8.tar.xz
fi

cd mesa-24.2.8

echo "Configuring with Meson (Turnip KGSL only)..."
rm -rf output
meson setup output \
  -Dgallium-drivers= \
  -Dvulkan-drivers=freedreno \
  -Dfreedreno-kmds=msm,kgsl \
  -Dplatforms=x11,wayland \
  -Dglx=disabled \
  -Dgbm=disabled \
  -Degl=disabled \
  -Dopengl=false \
  -Dgles1=disabled \
  -Dgles2=disabled \
  -Dllvm=disabled \
  -Dshared-llvm=disabled \
  -Dbuildtype=release \
  -Db_ndebug=true

echo "Building Turnip..."
ninja -C output src/freedreno/vulkan/libvulkan_freedreno.so

echo "Build complete! Checking library:"
ls -lh output/src/freedreno/vulkan/libvulkan_freedreno.so
strings output/src/freedreno/vulkan/libvulkan_freedreno.so | grep -i kgsl-3d0 || true
