<p align="center">
  <img src="./epfl_logo.png" width="250"/>
</p>

<h1 align="center">Distributed Algorithms 2025/26 – CS-451</h1>
<h3 align="center">École Polytechnique Fédérale de Lausanne (EPFL)</h3>
<h4 align="center">Professor Rachid Guerraoui</h4>

---

## Overview

This repository contains the implementation of the **CS-451 Distributed Algorithms** practical project at EPFL (2025/26).
The project consists of implementing several fundamental **distributed communication abstractions** that form the building blocks of fault-tolerant decentralized systems.

The project is divided into three milestones:

1. **Perfect Links** (Submission #1) – Reliable, exactly-once delivery between two processes over unreliable UDP.
2. **FIFO Uniform Reliable Broadcast** (Submission #2) – Broadcast abstraction ensuring FIFO order and reliability.
3. **Lattice Agreement** (Submission #3) – Consensus-like abstraction ensuring monotonic agreement among processes.

This repository currently focuses on **Milestone 1 (Perfect Links)**, with all relevant implementation files located under the `src/main/java/cs451/` directory.

---

## Project Aim

The goal of Milestone 1 is to implement **Perfect Point-to-Point Links (PL)** — a communication abstraction that guarantees:

* **No message creation:** Only messages actually sent are ever delivered.
* **No duplication:** Each message is delivered at most once.
* **Reliable delivery:** If both the sender and receiver are correct, every message sent is eventually delivered.

The implementation is built on top of **UDP**, which is inherently unreliable and unordered.
To achieve perfection despite these limitations, the project is layered as follows:

```
Perfect Link
   ↓
Stubborn Link
   ↓
Fair-Loss Link
   ↓
UDP (Datagram Sockets)
```

Each layer progressively adds reliability properties until the top layer (Perfect Link) satisfies all three guarantees.

---

## Project Structure

Below is an explanation of the key files and how they work together.

### `src/main/java/cs451/Main.java`

The main entry point of the application.
Responsible for:

* Parsing command-line arguments (`--id`, `--hosts`, `--output`, `--config`).
* Creating `Host` objects (process descriptions).
* Initializing the logging system.
* Building the link stack:

  * `UdpChannel`
  * `FairLossLinkImpl`
  * `StubbornLinkImpl`
  * `PerfectLinkImpl`
* Starting the protocol and sending messages as per the project requirements.
* Ensuring graceful shutdown and proper log flushing.

---

### `src/main/java/cs451/links/perfect/PerfectLinkImpl.java`

**Implements the Perfect Link abstraction.**

Responsibilities:

* Provides reliable, exactly-once delivery using:

  * **ACK messages** for confirmation.
  * **Duplicate suppression** via a delivered set.
  * **Stubborn retransmissions** (delegated to `StubbornLinkImpl`).
* Implements message batching for throughput optimization.
* Encodes messages with type headers (`DATA`, `ACK`, or `BATCH`).
* Sends ACKs unreliably (once per received DATA) to reduce congestion.
* Removes delivered DATA messages from the stubborn resend queue upon ACK receipt.
* Uses efficient numeric keys `(senderId, seq)` to track delivered messages with low memory overhead.

---

### `src/main/java/cs451/links/stubborn/StubbornLinkImpl.java`

**Implements the Stubborn Link abstraction.**

Responsibilities:

* Ensures every sent message is retransmitted periodically until acknowledged.
* Maintains a dedicated thread that periodically resends all pending DATA messages.
* Uses a resend interval (default: 20 ms, adjustable) for adaptive performance.
* Provides `sendOnce()` for one-shot, non-persistent sends (used for ACKs).
* Prevents blocking in the receive path by deferring actual sends to the resend loop.

---

### `src/main/java/cs451/links/fairloss/FairLossLinkImpl.java`

**Implements the Fair-Loss Link abstraction** directly on top of UDP.

Responsibilities:

* Provides a simple unreliable send/receive interface.
* Uses a bounded `ArrayBlockingQueue` for outgoing packets (1 million entries).
* Employs non-blocking queue insertion to avoid stalling the receiver thread.
* Simulates fair loss — messages can be dropped but are never duplicated or corrupted.
* Spawns separate TX and RX threads for concurrent send and receive operations.
* Invokes registered callbacks upon message arrival.

---

### `src/main/java/cs451/net/UdpChannel.java`

A lightweight wrapper around Java’s `DatagramSocket`.

Enhancements:

* Increased socket buffer sizes (8 MB send/receive) to handle high throughput.
* Reuses `DatagramPacket` objects to reduce GC pressure.
* Provides convenient `send()` and `receive()` methods.
* Ensures safe access through synchronized packet reuse (since only one RX/TX thread each).

---

### `src/main/java/cs451/LineLogger.java`

Buffered logging utility optimized for the large number of message delivery events.

Features:

* Writes lines in the format `d <sender> <seq>`.
* Uses an internal buffer and flushes periodically (default every 16k lines).
* Reduces I/O overhead significantly compared to per-write flushing.

---

### Other Supporting Files

* `src/main/java/cs451/Host.java` — Represents a process in the distributed system (id, IP, port).
* `src/main/java/cs451/utils/Parser.java` — Handles parsing of command-line arguments.
* `example/run.sh` — Shell script to launch processes with appropriate parameters.
* `tools/stress.py` — Provided benchmark script for high-load testing and verification.

---

## Key Implementation Details and Optimizations

| Feature                      | Description                                                                                           |                                                                               |
| ---------------------------- | ----------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------- |
| **ACK Optimization**         | ACKs are sent unreliably (one-shot) to avoid infinite retransmission loops that congest the network.  |                                                                               |
| **Efficient Deduplication**  | Delivered messages tracked by compact `(senderId << 32)                                               | seq`keys in a`ConcurrentHashMap` set — avoids millions of string allocations. |
| **Queue Management**         | Non-blocking send queue in FLP ensures the receiver thread never blocks, maintaining high throughput. |                                                                               |
| **UDP Buffer Tuning**        | Enlarged OS send/receive buffers (8 MB each) to minimize packet drops under heavy load.               |                                                                               |
| **Batching**                 | Up to 8 messages are combined into one UDP packet to reduce packet header and system call overhead.   |                                                                               |
| **Stubborn Resend Interval** | Shortened to 20 ms (configurable) for faster recovery from transient packet loss.                     |                                                                               |
| **Threading Model**          | Each layer runs independent TX/RX threads, enabling full-duplex operation without lock contention.    |                                                                               |
| **Logging Efficiency**       | Buffered logger with delayed flush ensures minimal I/O latency even at multi-million message scales.  |                                                                               |

---

## How to Build and Run

### Requirements

* Java 17+
* Python 3.8+ (for stress test script)
* Linux or macOS recommended for consistent networking behavior.

### Compilation

```bash
cd template_java
mvn clean package
```

This generates the executable JAR under `target/`.

### Execution Example

```bash
# Process 1
./run.sh --id 1 --hosts ../example/hosts --output output_1.txt

# Process 2
./run.sh --id 2 --hosts ../example/hosts --output output_2.txt
```

Each process logs delivered messages to its specified output file.

---

## Stress Testing

You can test reliability and performance using the provided `tools/stress.py` script.

Example:

```bash
python3 ../tools/stress.py perfect -r ./run.sh -l ../example/output -p 10 -m 10000000
```

Where:

* `perfect` specifies Milestone 1 mode.
* `-p` defines the number of processes.
* `-m` sets the number of messages each process sends.

The system should deliver all messages exactly once, even under high load.

---

## Design Philosophy

The design follows the **separation of concerns** principle:

* Each layer provides a clearly defined interface and guarantees.
* Layers are independent and composable.
* The system is resilient to packet loss, duplication, and reordering.
* Efficiency is prioritized without sacrificing correctness.

Through these abstractions, higher-level protocols (e.g., FIFO Broadcast, Lattice Agreement) can be built seamlessly atop the Perfect Link layer.

---

## References

* Guerraoui, R. & Schiper, A. (2001). *Software-Based Replication for Fault Tolerance.*
* CS-451 Course Materials (EPFL).
* [Java DatagramSocket API Documentation](https://docs.oracle.com/javase/8/docs/api/java/net/DatagramSocket.html)

---

## Authors

**Oscar de Francesca**
EPFL – MSc Computer Science
Distributed Algorithms (CS-451) – Spring 2025/26

---

> “Building reliable systems atop unreliable components is the essence of distributed computing.”
