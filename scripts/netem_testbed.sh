#!/usr/bin/env bash
# Real-kernel testbed on ONE Linux host: two network namespaces joined by a veth pair, each
# direction shaped by tc-netem (rate, delay = RTT/2, loss, drop-tail limit = 1 BDP). Unlike the
# in-process emulator, loss here also hits kernel TCP (CUBIC/BBR), so TCP results are comparable.
#
#   sudo ./scripts/netem_testbed.sh                      # all scenarios, all protocols
#   sudo PROTOCOLS="tcp-nio rbudp tsunami" TRIALS=10 SIZE=100MB ./scripts/netem_testbed.sh
#   then: java -jar target/ftp-bench.jar summarize --raw results/netem/raw.csv --out results/netem
#
# Requires: root, iproute2 (ip, tc), Java 25, the jar built with `mvn package`.
set -euo pipefail

JAR=${JAR:-target/ftp-bench.jar}
TRIALS=${TRIALS:-5}
SIZE=${SIZE:-10MB}
OUT=${OUT:-results/netem}
PROTOCOLS=${PROTOCOLS:-"tcp-stream tcp-nio tcp-parallel udp-blast rbudp rbudp-fec tsunami udp-saw udp-gbn udp-sr udp-sr-aimd"}
TCP_CC=${TCP_CC:-cubic}           # e.g. cubic, reno, bbr (if the module is available)
PORT=9000
# name  rate(Mbit)  rtt(ms)  loss(%)  jitter(ms)
SCENARIOS=${SCENARIOS:-"
lan        1000  1    0     0
metro      100   10   0.01  0
wan        100   50   0.1   0
lossy-wan  100   50   1     0
high-loss  50    100  5     0
mobile     50    40   2     5
satellite  20    600  0.5   0
"}

A=ftpA; B=ftpB
cleanup() { ip netns del $A 2>/dev/null || true; ip netns del $B 2>/dev/null || true; }
trap cleanup EXIT
cleanup
ip netns add $A; ip netns add $B
ip link add vethA netns $A type veth peer name vethB netns $B
ip -n $A addr add 10.77.0.1/24 dev vethA; ip -n $B addr add 10.77.0.2/24 dev vethB
ip -n $A link set vethA up; ip -n $B link set vethB up
ip -n $A link set lo up; ip -n $B link set lo up
for ns in $A $B; do
  ip netns exec $ns sysctl -qw net.ipv4.tcp_congestion_control="$TCP_CC"
  ip netns exec $ns sysctl -qw net.core.rmem_max=67108864 net.core.wmem_max=67108864 || true
  ip netns exec $ns sysctl -qw net.ipv4.tcp_rmem="4096 131072 67108864" net.ipv4.tcp_wmem="4096 131072 67108864"
done
# kernel offloads would let netem see 64 KB super-packets; disable for per-packet fidelity
ip netns exec $A ethtool -K vethA tso off gso off gro off 2>/dev/null || true
ip netns exec $B ethtool -K vethB tso off gso off gro off 2>/dev/null || true

mkdir -p "$OUT"
DATA="$OUT/data.bin"
java -jar "$JAR" gen --size "$SIZE" --out "$DATA" >/dev/null

echo "$SCENARIOS" | while read -r name rate rtt loss jitter; do
  [ -z "${name:-}" ] && continue
  half=$(awk "BEGIN{print $rtt/2}")
  limit=$(awk "BEGIN{b=$rate*1e6/8*$rtt/1000/1500; print (b<64)?64:int(b)+1}")   # 1 BDP in packets
  jit=""; [ "$jitter" != "0" ] && jit="${jitter}ms distribution normal"
  for dev in "$A vethA" "$B vethB"; do
    set -- $dev
    ip netns exec "$1" tc qdisc replace dev "$2" root netem rate "${rate}mbit" delay "${half}ms" $jit loss "${loss}%" limit "$limit"
  done
  pace=$(awk "BEGIN{printf \"%d\", $rate*1e6*0.95}")
  echo "=== $name: ${rate} Mbit/s, RTT ${rtt} ms, loss ${loss}%, jitter ${jitter} ms (netem limit ${limit} pkts)"
  for p in $PROTOCOLS; do
    ip netns exec $B java -jar "$JAR" server --protocol "$p" --port $PORT --count "$TRIALS" --linger 2000 --rate "$pace" \
        > "$OUT/server-$name-$p.log" 2>&1 &
    srv=$!
    sleep 1.5
    ip netns exec $A java -jar "$JAR" send --protocol "$p" --host 10.77.0.2 --port $PORT --file "$DATA" \
        --trials "$TRIALS" --label "$name" --csv "$OUT/raw.csv" --rate "$pace" --timeout 600 || true
    wait $srv || true
    grep -c "sha256=$(sha256sum "$DATA" | cut -d' ' -f1)" "$OUT/server-$name-$p.log" \
        | xargs -I{} echo "    $p: {} / $TRIALS transfers verified by SHA-256"
  done
done
echo "done: $OUT/raw.csv  ->  java -jar $JAR summarize --raw $OUT/raw.csv --out $OUT"
