#!/bin/sh
ROOTFS=/data/user/0/com.nativOS/files/rootfs
mkdir -p $ROOTFS/dev $ROOTFS/dev/pts $ROOTFS/sys $ROOTFS/proc $ROOTFS/tmp
mountpoint -q $ROOTFS/dev || mount -o bind /dev $ROOTFS/dev
mountpoint -q $ROOTFS/dev/pts || mount -t devpts devpts $ROOTFS/dev/pts
mountpoint -q $ROOTFS/sys || mount -o bind /sys $ROOTFS/sys
mountpoint -q $ROOTFS/proc || mount -t proc proc $ROOTFS/proc

chroot $ROOTFS "$@"
