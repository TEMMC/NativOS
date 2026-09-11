#!/bin/sh
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export LD_LIBRARY_PATH=/usr/lib:/lib
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
export VK_LOADER_DEBUG=all
export TU_DEBUG=all
/usr/bin/vulkaninfo --summary 2>&1
