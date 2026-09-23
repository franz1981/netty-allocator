#!/bin/bash
# LLC / cache-miss counters are not in JMH perfnorm's default event set on this box; ask for them explicitly.
set -u
O=/home/forked_franz/IdeaProjects/netty-bench/results/h2h-merged-x86-2026-09-22/ffm/prof
cd /home/forked_franz/IdeaProjects/netty-bench/results/h2h-merged-x86-2026-09-22/ || exit 1
LAO=/tmp/claude-1000/-home-forked-franz-IdeaProjects-netty/01a26e43-89a8-433b-a89e-c560138e4359/scratchpad/lao
J=$LAO/benchmark/target/benchmarks.jar
A=$LAO/ffm-allocator/target/ffm-allocator-1.2.4.Final-SNAPSHOT.jar
MI=/home/forked_franz/IdeaProjects/netty-ffm-allocator/native/mimalloc/out/release/libmimalloc.so
JAVA=$HOME/.sdkman/candidates/java/25.0.3-tem/bin/java
VM="-XX:MaxRAM=60g --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Dffm.mimalloc.lib=$MI"
P="-p sizePattern=E_COMMERCE -p enableReadWrite=true -p MAX_LIVE_BUFFERS=1024 -t 32 -f 1 -wi 5 -i 5"
B=ByteBufAllocatorAllocPatternBenchmark.directAllocation
EV="cycles,instructions,cache-references,cache-misses,LLC-loads,LLC-load-misses,LLC-stores,LLC-store-misses,branch-misses,stalled-cycles-frontend"

wait_idle() {
  while true; do
    busy=""
    for p in $(pgrep -x java); do
      c=$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null)
      case "$c" in *org.openjdk.jmh.Main*|*ForkedMain*|*benchmarks.jar*|*CycleScoped*|*E2EServer*|*TopoServer*) busy="yes";; esac
    done
    pgrep -x h2load >/dev/null && busy="yes"
    [ -z "$busy" ] && break
    echo "   ... waiting 30 s $(date +%T)"; sleep 30
  done
}

for c in /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq; do echo 2300000 | sudo -n tee $c >/dev/null; done
echo "freq cpu0=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq) start $(date +%T)"
for ex in platform eventloop; do
  EXV="$VM"; [ $ex = platform ] && EXV="$VM -Djmh.executor=PLATFORM"
  wait_idle
  echo "== perfnorm-llc-$ex $(date +%T)"
  numactl --cpunodebind=0 --preferred=0 $JAVA -cp $J:$A org.openjdk.jmh.Main "$B" \
    -p allocatorType=ADAPTIVE,MIMALLOC,FFM_MI $P -prof "perfnorm:events=$EV" \
    -jvmArgsAppend "$EXV" > $O/perfnorm-llc-$ex.txt 2>&1
  echo "   exit=$? $(date +%T)"
done
for c in /sys/devices/system/cpu/cpu*/cpufreq/scaling_max_freq; do echo 4300000 | sudo -n tee $c >/dev/null; done
echo "freq restored done $(date +%T)"
