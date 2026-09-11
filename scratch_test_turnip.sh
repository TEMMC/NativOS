#!/bin/sh
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DISPLAY=:0
export MESA_LOADER_DRIVER_OVERRIDE=zink
export GALLIUM_DRIVER=zink
export TU_DEBUG=noconform
export ZINK_DESCRIPTORS=lazy
export MESA_VK_WSI_DEBUG=sw
echo "=== GLXINFO ==="
glxinfo -B 2>&1
echo "=== EGLINFO ==="
eglinfo 2>&1 | head -n 30
