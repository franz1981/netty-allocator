#!/bin/bash
# Why is FFM_MI faster than ADAPTIVE and the Java mimalloc port on platform threads, and why does the gap
# close on event loops? One fork per cell, 1024 live buffers, 32 threads, E_COMMERCE, read+write enabled.
set -u
O=/home/forked_franz/IdeaProjects/netty-bench/results/h2h-merged-x86-2026-09-22/ffm/prof
cd /home/forked_franz/IdeaProjects/netty-bench/results/h2h-merged-x86-2026-09-22/ || exit 1
LAO=/tmp/claude-1000/-home-forked-franz-IdeaProjects-netty/01a26e43-89a8-433b-a89e-c560138e4359/scratchpad/lao
J=$LAO/benchmark/target/benchmarks.jar
A=$LAO/ffm-allocator/target/ffm-allocator-1.2.4.Final-SNAPSHOT.jar
# The library the v1/v2 tables were measured with: same commit and cmake flags as native/build-mimalloc.sh,
# different build directory.
MI=/home/forked_franz/IdeaProjects/netty-ffm-allocator/native/mimalloc/out/release/libmimalloc.so
AP=/home/forked_franz/async-profiler/lib/libasyncProfiler.so
JAVA=$HOME/.sdkman/candidates/java/25.0.3-tem/bin/java
VM="-XX:MaxRAM=60g --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Dffm.mimalloc.lib=$MI"
P="-p sizePattern=E_COMMERCE -p enableReadWrite=true -p MAX_LIVE_BUFFERS=1024 -t 32 -f 1 -wi 5 -i 5"
B=ByteBufAllocatorAllocPatternBenchmark.directAllocation
ALL=ADAPTIVE,MIMALLOC,FFM_MI

wait_idle() {
  while true; do
    busy=""
    for p in $(pgrep -x java); do
      c=$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null)
      case "$c" in
        *org.openjdk.jmh.Main*|*ForkedMain*|*benchmarks.jar*|*CycleScoped*|*E2EServer*|*TopoServer*) busy="yes";;
      esac
    done
    pgrep -x h2load >/dev/null && busy="yes"
    [ -z "$busy" ] && break
    echo "   ... another benchmark is running, waiting 30 s $(date +%T)"
    sleep 30
  done
}

go() { n=$1; shift; wait_idle; echo "== $n $(date +%T)"; numactl --cpunodebind=0 --preferred=0 $JAVA -cp $J:$A org.openjdk.jmh.Main "$B" "$@" > $O/$n.txt 2>&1; echo "   exit=$? $(date +%T)"; }

for c in /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq; do echo 2300000 | sudo -n tee $c >/dev/null; done
echo "freq cpu0=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq) start $(date +%T)"

go perfnorm-platform  -p allocatorType=$ALL $P -prof perfnorm -jvmArgsAppend "$VM -Djmh.executor=PLATFORM"
go perfnorm-eventloop -p allocatorType=$ALL $P -prof perfnorm -jvmArgsAppend "$VM"

for a in ADAPTIVE MIMALLOC FFM_MI; do
  go perfasm-platform-$a  -p allocatorType=$a $P -prof "perfasm:event=cycles;top=40" \
     -jvmArgsAppend "$VM -Djmh.executor=PLATFORM"
done
for a in ADAPTIVE MIMALLOC FFM_MI; do
  go perfasm-eventloop-$a -p allocatorType=$a $P -prof "perfasm:event=cycles;top=40" -jvmArgsAppend "$VM"
done
for a in ADAPTIVE MIMALLOC FFM_MI; do
  mkdir -p $O/async-$a
  go async-platform-$a -p allocatorType=$a $P \
     -prof "async:libPath=$AP;event=cpu;interval=1000000;output=collapsed;dir=$O/async-$a" \
     -jvmArgsAppend "$VM -Djmh.executor=PLATFORM"
done

for c in /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq; do echo 4300000 | sudo -n tee $c >/dev/null; done
echo "freq restored $(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq) done $(date +%T)"
