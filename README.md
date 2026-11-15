# File Transfer Protocol Research Benchmark

A single Java 25 program that contains **11 bulk file-transfer protocols**, an **in-process network
emulator**, and a **benchmark harness** that produces raw data, summary statistics (mean ± 95% CI),
ranked Markdown tables, IEEE-ready LaTeX tables and (via a script) publication figures.

```
mvn package                                    # builds target/ftp-bench.jar (runs the tests too)
java -jar target/ftp-bench.jar list            # protocols + scenarios
java -jar target/ftp-bench.jar bench --suite quick
```

---

## 1. Protocols

| Name | Transport | Family | What it is |
|---|---|---|---|
| `tcp-stream` | TCP | Baseline 1 | Blocking `java.net.Socket` streams, user-space copy. |
| `tcp-nio` | TCP | Baseline 1 | `SocketChannel` + `FileChannel.transferTo/From` (zero-copy where the OS supports it). |
| `tcp-parallel` | TCP | Striping | N parallel TCP connections over contiguous file stripes (GridFTP / bbcp style). |
| `udp-blast` | UDP | Reference 2 | Paced raw UDP, **no reliability**: upper bound on speed, lower bound on correctness. |
| `rbudp` | TCP+UDP | Hybrid 3 | Reliable Blast UDP: UDP blast rounds, missing-chunk bitmap over TCP, repeat. |
| `rbudp-fec` | TCP+UDP | Hybrid 3 + FEC | RBUDP + first-round XOR parity (1 per k chunks) to repair single losses without a round. |
| `tsunami` | TCP+UDP | Hybrid 3 | Tsunami-style: continuous rate-based UDP stream, periodic NAKs + loss-rate feedback, multiplicative rate adaptation. |
| `udp-saw` | UDP | Classic ARQ | Stop-and-Wait (window 1), i.e. TFTP-like behaviour. |
| `udp-gbn` | UDP | Classic ARQ | Go-Back-N: cumulative ACKs, single timer, whole-window resend. |
| `udp-sr` | UDP | Classic ARQ | Selective Repeat: SACK + cumulative ACK, per-packet timers, fast retransmit (3 later ACKs). |
| `udp-sr-aimd` | UDP | ARQ + CC | Selective Repeat + Reno-style AIMD (IW10, slow start, halve once per window, cwnd=1 on RTO). |

All ARQ variants use the RFC 6298 retransmission timer with Karn's rule. Backoff is cleared when
new data is acknowledged, as Linux does.

### Wire formats (big-endian)
* **TCP baselines**: `[fileSize:i64][bytes…]` → receiver replies 1 ACK byte once the file is on disk.
* **udp-blast / ARQ data**: `[type:u8][seq:i32][chunkSize:i32][fileSize:i64][payload]`; ARQ ACK: `[2][cumAck:i32][seq:i32]`.
* **RBUDP**: UDP `[1][seq:i32][payload]`, parity `[3][group:i32][xor]`; TCP `HELLO(size,chunk,k)`, `READY`,
  `ROUND_END(round,sent)`, `REPORT(missing, bitmapLen, bitmap)`.
* **Tsunami**: UDP `[1|4][seq:i32][payload]` (original | retransmission); TCP `HELLO`, `READY`, `START(rtt)`,
  then receiver→sender `REQ(n, seq…)`, `ERR(ppm)`, `DONE`.

Source: [src/main/java/com/yashgamerx/proto](src/main/java/com/yashgamerx/proto).

---

## 2. Network emulation

### 2.1 Built-in (in-process) emulator — runs on Windows/macOS/Linux, no admin rights
[`emu/`](src/main/java/com/yashgamerx/emu) places a UDP relay and a TCP relay between sender and
receiver. Each direction is a **Link**: drop-tail FIFO (default 1 BDP, min 64 KiB) drained at the
link rate, then propagation delay = RTT/2 (± Gaussian jitter, which can reorder packets), then a loss
process (Bernoulli or Gilbert–Elliott bursts). UDP datagrams are charged +28 B (IP+UDP) and TCP
segments +40 B per 1460 B. **TCP control channels and UDP data share the same bottleneck**, as they
would on a real path.

| Scenario | Rate | RTT | Loss | Notes |
|---|---|---|---|---|
| `loopback` | host | ~0 | 0 | no emulation, host-limited upper bound |
| `lan` | 1 Gb/s | 1 ms | 0 | stresses per-packet CPU cost |
| `metro` | 100 Mb/s | 10 ms | 0.01% | |
| `wan` | 100 Mb/s | 50 ms | 0.1% | |
| `lossy-wan` | 100 Mb/s | 50 ms | 1% | |
| `burst-wan` | 100 Mb/s | 50 ms | ~1% | Gilbert–Elliott, mean burst 10 packets |
| `high-loss` | 50 Mb/s | 100 ms | 5% | |
| `mobile` | 50 Mb/s | 40 ms ± 5 ms | 2% | jitter ⇒ reordering |
| `satellite` | 20 Mb/s | 600 ms | 0.5% | GEO-like, large BDP |
| `custom:rate=…,rtt=…,loss=…,jitter=…,burst=…,queue=…,fwdonly=…,name=…` | | | | any condition |

> **Important limitation (state it in the paper).** A user-space relay terminates TCP, so it cannot
> drop segments of a kernel TCP connection. The emulator applies **rate, delay and a window/RTT bound**
> to TCP: each relay direction allows only `--tcp-buffer` bytes in flight across the emulated pipe, and
> credit returns one reverse delay after delivery, so throughput ≤ min(rate, window/RTT). It does **not**
> apply **loss** to TCP. In lossy scenarios the TCP rows are marked † and are *upper bounds*. For
> TCP-under-loss numbers use the netem testbed (§2.2). `udp-sr-aimd` is a useful cross-check: its
> goodput under random loss follows the Mathis et al. model (rate ≈ MSS/RTT · 1.22/√p). In a smoke
> test at 1% loss and 20 ms RTT it measured 6.1 Mb/s against a predicted 6.8 Mb/s.

### 2.2 Real-kernel testbed (Linux, recommended for final paper numbers)
[`scripts/netem_testbed.sh`](scripts/netem_testbed.sh) creates two network namespaces joined by a
veth pair and shapes **each direction** with `tc netem` (rate, delay, jitter, loss, 1-BDP limit),
disables TSO/GSO/GRO, then runs `server`/`send` for every protocol and scenario:

```bash
mvn -DskipTests package
sudo TRIALS=10 SIZE=100MB TCP_CC=cubic ./scripts/netem_testbed.sh
java -jar target/ftp-bench.jar summarize --raw results/netem/raw.csv --out results/netem
python scripts/plot_results.py results/netem
```
Re-run with `TCP_CC=bbr` to add a BBR baseline. Two physical machines work the same way. Start
`server` on one host and `send` on the other, shaping with netem on either NIC. On Windows,
[clumsy](https://jagt.github.io/clumsy/) can add loss and delay to real sockets; use `--scenarios external:<label>`.

---

## 3. Running the benchmark

```bash
java -jar target/ftp-bench.jar bench --suite quick       # 3 scenarios, 10 MiB, 3 trials   (~minutes)
java -jar target/ftp-bench.jar bench --suite standard    # 8 scenarios, 1/10/50 MiB, 5 trials
java -jar target/ftp-bench.jar bench --suite paper       # 9 scenarios, 1/10/100 MiB, 10 trials (hours)

# any subset / override
java -jar target/ftp-bench.jar bench --protocols all,-udp-saw --scenarios wan,lossy-wan,satellite \
     --sizes 10MB,100MB --trials 10 --timeout 300
java -jar target/ftp-bench.jar bench --scenarios custom:name=geo,rate=20,rtt=600,loss=0.005,burst=4

# parameter sweeps (ablation studies) – run once per value
java -jar target/ftp-bench.jar bench --protocols rbudp,tsunami,udp-sr-aimd --scenarios wan --rate 50M
java -jar target/ftp-bench.jar bench --protocols rbudp-fec --scenarios lossy-wan --fec 8
java -jar target/ftp-bench.jar bench --protocols tcp-parallel --scenarios satellite --streams 8
java -jar target/ftp-bench.jar bench --protocols udp-blast,rbudp --scenarios lan --chunk 8000   # jumbo datagrams
```
Then generate figures (needs `pip install matplotlib`):
```bash
python scripts/plot_results.py results/<timestamp>
```

### Outputs (`results/<timestamp>/`)
| File | Content |
|---|---|
| `environment.txt` | JVM, OS, CPU count, every parameter. Cite it in the methodology section. |
| `raw.csv` | One row per trial: status, completion time, goodput, overhead, packet/retransmission/FEC counters, emulator drops. |
| `summary.csv` | Per (scenario, size, protocol): n, success, goodput mean/sd/CI95/median/min/max, time, overhead, retrans %, link utilisation. |
| `summary.md` | Ranked tables per scenario × size. |
| `summary.tex` | `table*` per file size: protocols × scenarios, mean ± CI, best in bold, † markers. |
| `figures/` | (from the script) grouped goodput / overhead bars, time-vs-size, box plots. |
| `pairwise_tests.csv` | (from the script) Welch t-test p-values between every pair of protocols. |

### Metrics
* **Completion time**: sender start → last byte of the complete file at the receiver. For `udp-blast`, it ends at the last datagram that arrived.
* **Goodput**: unique file bytes × 8 / completion time. This is the main figure of merit.
* **Integrity**: SHA-256 of the received file equals the source. A reliable protocol that fails it is reported as `CORRUPT`.
* **Delivered %**: the correctness metric for `udp-blast`.
* **Overhead ratio**: (all bytes sent by both ends, including headers, ACKs, NAKs, parity and retransmissions) / file size.
* **Retransmission %**: retransmitted data packets / original data packets.
* **Link utilisation**: goodput / emulated link rate.
* Statuses: `OK`, `INCOMPLETE` (best effort, missing data), `CORRUPT`, `TIMEOUT`, `ERROR`, `SKIPPED`. After a cell's first timeout, its remaining trials are skipped (`--no-skip` disables this).

### Methodology built into the harness
* Deterministic incompressible random files (seeded), hashed once, and a fresh emulator per trial seeded by `(seed, trial)`.
* JIT warm-up runs per protocol (`--warmup`), and `System.gc()` before each trial.
* **Randomised protocol order inside each trial block**, so slow host drift does not favour one protocol.
* UDP protocols are paced at `--rate-fraction` (default 0.95) × the link rate unless `--rate` is given.
  Report the pacing rate, because RBUDP/Tsunami performance depends on it.
* Student-t 95% confidence intervals. Use `--trials 10` or more for the paper (Jain 1991; Hoefler & Belli 2015).

### Threats to validity (state these in the paper)
1. In-process emulation does not apply loss to TCP; see §2.1. Use netem for the TCP comparison.
2. Sender, receiver and emulator share one host. At ≥ 1 Gb/s with 1400 B datagrams, user-space UDP on
   Windows is limited by per-datagram system calls: about 250–580 Mb/s even on raw loopback, but
   1–2.5 Gb/s with `--chunk 8000`. Kernel TCP gets segmentation offload and zero-copy, so `lan` partly
   measures host cost. That is a real finding, but describe it as such.
3. Windows timers have about 1.5 ms resolution. The emulator spins for the final 3 ms of each wait,
   which costs CPU. Close other programs and use a high-performance power plan.
4. The Gilbert–Elliott chain moves forward once per *link packet-time* (1500 B at the link rate),
   using the closed-form n-step transition. A burst is therefore a stretch of channel time, like a
   fade, and an idle or backed-off sender is not held inside one burst. netem's `gemodel` steps once
   per packet instead, so the two agree only for back-to-back traffic.
5. The Tsunami rate-control constants (25/24 slow-down, 5/6 speed-up, 7.5% threshold) are modelled on
   tsunami-udp's defaults. Check them against the exact release you cite.

---

## 4. Other algorithms worth adding to the study

Already implemented above: TCP (streams, zero-copy, parallel), raw UDP, RBUDP, RBUDP+FEC, Tsunami,
Stop-and-Wait, Go-Back-N, Selective Repeat, and Selective Repeat with AIMD.

Candidates to add next, roughly in order of research value:

| Algorithm | Why include it | How |
|---|---|---|
| **UDT** (Gu & Grossman 2007) | The best-known reliable-UDP protocol for high-BDP paths (DAIMD rate control + NAK). | Implement its rate control in `ArqProtocol`/Tsunami style, or use the C++ library via JNI. |
| **QUIC** (RFC 9000) with CUBIC/BBR | The modern standard for reliable transport over UDP. | Java libraries such as *kwik* or *netty-incubator-codec-quic*. |
| **TCP CUBIC vs BBR vs Reno** (RFC 9438; Cardwell 2016) | Shows how much depends on the congestion controller. | netem testbed: `TCP_CC=bbr` / `reno` / `cubic`. |
| **Fountain codes** (LT, Luby 2002; RaptorQ RFC 6330) | Rateless FEC with no retransmission rounds; the natural next step after `rbudp-fec`. | Replace XOR parity with an LT/RaptorQ encoder (e.g. OpenRQ). |
| **Reed–Solomon FEC** (Rizzo 1997) | Repairs several losses per group, unlike XOR. | Swap the parity routine. |
| **PCC / PCC-Vivace** (Dong 2015/2018) | Learning-based rate control that handles random loss well. | Utility-based rate controller on top of Tsunami's data path. |
| **TCP Hybla** (Caini 2004) | Designed for satellite RTTs. | netem testbed, `TCP_CC=hybla`. |
| **SCTP** (RFC 9260) / **MPTCP** (RFC 8684) | Multi-streaming and multipath transport. | Kernel support on Linux; JDK has `com.sun.nio.sctp` on Linux. |
| **LEDBAT** (RFC 6817) | Background transfer that gives way to other traffic. | Delay-based window on top of `udp-sr`. |
| **GridFTP** (Allcock 2005) / **Aspera FASP** | Production reference points. | Run the real tools on the netem testbed (FASP is proprietary). |

---

## 5. References (IEEE style)

[1] E. He, J. Leigh, O. Yu, and T. A. DeFanti, "Reliable Blast UDP: Predictable high performance bulk data transfer," in *Proc. IEEE Int. Conf. Cluster Computing (CLUSTER)*, 2002, pp. 317–324.
[2] M. R. Meiss, "Tsunami: A high-speed rate-controlled protocol for file transfer," Indiana Univ., Bloomington, IN, USA, Tech. Rep., 2002. (tsunami-udp: https://tsunami-udp.sourceforge.net/)
[3] Y. Gu and R. L. Grossman, "UDT: UDP-based data transfer for high-speed wide area networks," *Computer Networks*, vol. 51, no. 7, pp. 1777–1799, 2007.
[4] W. Allcock *et al.*, "The Globus striped GridFTP framework and server," in *Proc. ACM/IEEE Supercomputing (SC)*, 2005.
[5] T. J. Hacker, B. D. Athey, and B. Noble, "The end-to-end performance effects of parallel TCP sockets on a lossy wide-area network," in *Proc. IEEE IPDPS*, 2002.
[6] M. Mathis, J. Semke, J. Mahdavi, and T. Ott, "The macroscopic behavior of the TCP congestion avoidance algorithm," *ACM SIGCOMM Comput. Commun. Rev.*, vol. 27, no. 3, pp. 67–82, 1997.
[7] J. Padhye, V. Firoiu, D. Towsley, and J. Kurose, "Modeling TCP throughput: A simple model and its empirical validation," in *Proc. ACM SIGCOMM*, 1998.
[8] V. Jacobson, "Congestion avoidance and control," in *Proc. ACM SIGCOMM*, 1988.
[9] P. Karn and C. Partridge, "Improving round-trip time estimates in reliable transport protocols," in *Proc. ACM SIGCOMM*, 1987.
[10] V. Paxson, M. Allman, J. Chu, and M. Sargent, "Computing TCP's retransmission timer," IETF RFC 6298, 2011.
[11] J. Chu, N. Dukkipati, Y. Cheng, and M. Mathis, "Increasing TCP's initial window," IETF RFC 6928, 2013.
[12] E. N. Gilbert, "Capacity of a burst-noise channel," *Bell Syst. Tech. J.*, vol. 39, no. 5, pp. 1253–1265, 1960.
[13] E. O. Elliott, "Estimates of error rates for codes on burst-noise channels," *Bell Syst. Tech. J.*, vol. 42, no. 5, pp. 1977–1997, 1963.
[14] S. Hemminger, "Network emulation with NetEm," in *Proc. linux.conf.au*, 2005.
[15] J. Iyengar and M. Thomson, "QUIC: A UDP-based multiplexed and secure transport," IETF RFC 9000, 2021.
[16] N. Cardwell, Y. Cheng, C. S. Gunn, S. H. Yeganeh, and V. Jacobson, "BBR: Congestion-based congestion control," *ACM Queue*, vol. 14, no. 5, 2016.
[17] S. Ha, I. Rhee, and L. Xu, "CUBIC: A new TCP-friendly high-speed TCP variant," *ACM SIGOPS Oper. Syst. Rev.*, vol. 42, no. 5, 2008.
[18] M. Dong, Q. Li, D. Zarchy, P. B. Godfrey, and M. Schapira, "PCC: Re-architecting congestion control for consistent high performance," in *Proc. USENIX NSDI*, 2015.
[19] M. Luby, "LT codes," in *Proc. IEEE FOCS*, 2002.
[20] L. Rizzo, "Effective erasure codes for reliable computer communication protocols," *ACM SIGCOMM Comput. Commun. Rev.*, vol. 27, no. 2, 1997.
[21] K. Sollins, "The TFTP protocol (revision 2)," IETF RFC 1350, 1992.
[22] R. Jain, *The Art of Computer Systems Performance Analysis*. New York, NY, USA: Wiley, 1991.
[23] T. Hoefler and R. Belli, "Scientific benchmarking of parallel computing systems," in *Proc. ACM/IEEE SC*, 2015.

Check every reference against the publisher's record before you submit.

---

## 6. Code map
```
core/   Protocol API, Config, TransferStats, mmap Source/SinkFile, Pacer, RFC 6298 RttEstimator
emu/    Link (rate+queue+delay+loss), LossModel (Bernoulli, Gilbert-Elliott), UdpProxy, TcpProxy, Scenario
proto/  the 11 protocols + registry
bench/  BenchRunner (factorial experiment), TrialRunner, Stats (t-CI), Summary (csv/md/tex), Remote (two-host)
scripts/plot_results.py   IEEE figures + Welch tests      scripts/netem_testbed.sh   Linux kernel testbed
```
