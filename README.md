# Netty mimalloc allocator

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java Version](https://img.shields.io/badge/Java-1.8%2B-orange.svg)](https://www.oracle.com/java/technologies/javase-jdk8-downloads.html)
[![Netty Version](https://img.shields.io/badge/Netty-4.2.10.Final%2B-orange.svg)](https://netty.io/)
[![Virtual Thread Friendly](https://img.shields.io/badge/Virtual_Threads-Friendly-green.svg)](https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html)

A high-performance Java port of the **mimalloc** allocator, tailored for **Netty**.

---

## 🚀 Key Features

* **Mimalloc Powered**: Leverages mimalloc's advanced allocation strategies (free lists, local shards).
* **Tailored for Netty**: Specifically designed to handle Netty's `ByteBuf` allocation with minimal overhead.
* **High Throughput**: Optimized for multi-threaded network environments to ensure high and stable performance.
* **Virtual-Threads Friendly**: Optimized for Project Loom, preventing the memory explosion typically caused by massive `ThreadLocal` usage in virtual thread scenarios.
---

## ⚓ Requirements

| Requirement | Minimum Version |
| :--- | :--- |
| **Java (JDK)** | **1.8** or higher |
| **Netty** | **4.2.10.Final** or newer |

---

## 📖 How to use

### 1. Maven dependencies:
Add the following dependencies to your `pom.xml`:

```xml
    <dependencies>
        <dependency>
            <groupId>io.github.neoionet</groupId>
            <artifactId>mimalloc</artifactId>
            <version>1.0.8.Final</version>
        </dependency>
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-common</artifactId>
            <version>4.2.10.Final</version>
        </dependency>
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-buffer</artifactId>
            <version>4.2.10.Final</version>
        </dependency>
    </dependencies>
```

If you want to use the mimalloc allocator within your server or client transport, ensure the `netty-transport` dependency is included in your project as well:
```xml
    <dependencies>
       <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-transport</artifactId>
            <version>4.2.10.Final</version>
        </dependency>
    </dependencies>
```

### 2. Quick start
#### 2.1. Initialize Allocator:
```java
// Create an instance of the mimalloc-based allocator.
ByteBufAllocator miMallocAllocator = new MiByteBufAllocator();
```

#### 2.2. Simple allocation:
```java
ByteBuf buf = miMallocAllocator.directBuffer();
buf.writeLong(123);
System.out.println(buf.readLong()); // print 123.
buf.release();
```

#### 2.3. Apply to Server:
```java
ServerBootstrap b = new ServerBootstrap();
b.group(bossGroup, workerGroup)
 .channel(NioServerSocketChannel.class)
 .option(ChannelOption.ALLOCATOR, miMallocAllocator) // Set the mimalloc allocator.
 .childOption(ChannelOption.ALLOCATOR, miMallocAllocator) // Set the mimalloc allocator for child.
 ...
```

#### 2.4. Apply to Client:
```java
Bootstrap b = new Bootstrap();
b.group(group)
 .channel(NioSocketChannel.class)
 .option(ChannelOption.ALLOCATOR, miMallocAllocator) // Set the mimalloc allocator.
 ...
```

---

## 🔧 Configuration Properties

*   **`-Dio.github.neoionet.allocator.mimalloc.segment.mib`**
    *   **Allowed Values:** `4`, `8`, `16`, `32`
    *   **Description:** Sets the segment size in MiB.
    *   **Default value:** `4`

*   **`-Dio.github.neoionet.allocator.mimalloc.page.search.strategy`**
    *   **Allowed Values:** `best`, `first`
    *   **Description:** Determines the page search strategy. `best` stands for **best-fit**, `first` stands for **first-fit**.
    *   **Default value:** `best`

## 🧪 Mixed memory test

`MixedMemoryTest` (in the `benchmark` module) shows how much memory an allocator keeps, and gives back, over time,
when event loops and platform threads share it. It prints one line per second:
`MEM <phase> <epoch_ms> <rss_kib> <jvm_direct_bytes> <jvm_direct_count> <allocator_used_direct>`.

Phases: `baseline` (5 s idle); `burst` (every thread allocates and holds buffers of every size class and some large
ones, in random order); `release` (each thread frees half of its own buffers and half of another thread's, event loops
and platform threads mixed, then 5 s idle); `traffic` (every thread allocates and frees one 1-16 KiB buffer per
millisecond).

```
mvn -pl benchmark -am package -DskipTests
java -cp benchmark/target/benchmarks.jar <JVM flags> io.github.neoionet.netty.microbenchmark.MixedMemoryTest \
     <adaptive|mimalloc> <eventLoops> <platformThreads> <perSizeClass> <perLargeSize> <trafficSeconds> \
     [largeEvery] [silent|active] [secondBurstAtSeconds]
```

Scenarios we run (8 event loops, 16 platform threads, 32 buffers per size class, 4 per large size, 60 s of traffic):

| scenario | arguments after the allocator |
| :--- | :--- |
| default | `8 16 32 4 60 0` |
| second burst 30 s into the traffic | `8 16 32 4 60 0 active 30` |
| trickle: 1 in 1000 traffic buffers is 256 KiB | `8 16 32 4 60 1000` |
| silent: platform threads stop after the burst | `8 16 32 4 60 0 silent` |

JVM flags, for every configuration (JDK 25): `--enable-native-access=ALL-UNNAMED -Dtouch=true
-Dio.netty.leakDetection.level=disabled -Xms2g -Xmx2g -XX:+AlwaysPreTouch -XX:MaxDirectMemorySize=16g`.
`-Dtouch=true` writes every 4 KiB page of each buffer, as an application filling its buffers would; a fixed,
pre-touched Java heap does not move RSS. Do not add `--sun-misc-unsafe-memory-access=allow`: with it Netty uses
`sun.misc.Unsafe`, and the direct memory it would `malloc` comes from `ByteBuffer.allocateDirect` instead, zeroed and
fully resident.

| configuration | allocator argument | extra |
| :--- | :--- | :--- |
| mimalloc (this project) | `mimalloc` | - |
| Netty `AdaptiveByteBufAllocator` | `adaptive` | to test another Netty build, put its `netty-common`, `netty-buffer` and `netty-transport` before the benchmarks jar on the class path |
| the same, page-store build ([franz1981/netty `adaptive-page-store`](https://github.com/franz1981/netty/tree/adaptive-page-store)), `mmap` regions | `adaptive` | that build first on the class path; `mmap` needs JDK 22+, Linux x86_64 or aarch64 and `--enable-native-access=ALL-UNNAMED`, else it falls back to `malloc` |
| the same, `malloc` regions | `adaptive` | `-Dio.netty.allocator.segmentRegionSize=0` |

## 🌟 Acknowledgments

This project would not be possible without the following open-source works:

* **[mimalloc](https://github.com/microsoft/mimalloc)** - A compact general purpose allocator with excellent performance.
* **[Netty](https://github.com/netty/netty)** - An event-driven asynchronous network application framework.

---

