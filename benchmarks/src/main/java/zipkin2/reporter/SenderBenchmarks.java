/*
 * Copyright 2016-2019 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin2.reporter;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import okio.Okio;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import zipkin2.CheckResult;
import zipkin2.Span;
import zipkin2.codec.SpanBytesDecoder;

/**
 * This benchmark reports spans as fast as possible. The sender clears the queue as fast as
 * possible using different max message sizes.
 */
@Measurement(iterations = 5, time = 1)
@Warmup(iterations = 10, time = 1)
@Fork(3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Threads(-1)
public abstract class SenderBenchmarks {
  /**
   * How many spans to keep in the backlog at one time. This number is high to ensure senders aren't
   * limited by span production speed.
   */
  static final int TARGET_BACKLOG = 1_000_000;

  // 64KiB, 1MB (default for Kafka), 5MiB, 16MiB (default for Scribe)
  @Param({"65536", "1000000", "5242880", "16777216"})

  public int messageMaxBytes;

  static final byte[] clientSpanBytes = spanFromResource("/zipkin2-client.json");
  static final Span clientSpan = SpanBytesDecoder.JSON_V2.decodeOne(clientSpanBytes);

  static final InMemoryReporterMetrics metrics = new InMemoryReporterMetrics();

  @AuxCounters
  @State(Scope.Thread)
  public static class InMemoryReporterMetricsAsCounters {

    public long spans() {
      return metrics.spans() - metrics.spansDropped();
    }

    public long messages() {
      return metrics.messages();
    }

    public long messagesDropped() {
      return metrics.messagesDropped();
    }

    @Setup(Level.Iteration)
    public void clean() {
      metrics.clear();
    }
  }

  Sender sender;
  AsyncReporter.BoundedAsyncReporter<Span> reporter;

  @Setup(Level.Trial)
  public void setup() throws Throwable {
    sender = createSender();

    CheckResult senderCheck = sender.check();
    if (!senderCheck.ok()) throw senderCheck.error();

    reporter = (AsyncReporter.BoundedAsyncReporter<Span>) AsyncReporter.builder(sender)
        .messageMaxBytes(messageMaxBytes)
        .queuedMaxSpans(TARGET_BACKLOG)
        .metrics(metrics).build();
  }

  protected abstract Sender createSender() throws Exception;

  @Setup(Level.Iteration)
  public void fillQueue() throws IOException {
    while (reporter.pending.offer(clientSpan, clientSpanBytes.length));
  }

  @TearDown(Level.Iteration)
  public void clearQueue() throws IOException {
    reporter.pending.clear();
  }

  @Benchmark
  public void report(InMemoryReporterMetricsAsCounters counters) throws InterruptedException {
    // if we were able to add more to the queue, that means the sender sent spans
    if (reporter.pending.offer(clientSpan, clientSpanBytes.length)) {
      metrics.incrementSpans(1);
    } else {
      Thread.sleep(10);
    }
  }

  @TearDown(Level.Trial)
  public void close() throws Exception {
    reporter.close();
    sender.close();
    afterSenderClose();
  }

  protected abstract void afterSenderClose() throws Exception;

  static byte[] spanFromResource(String jsonResource) {
    InputStream stream = SenderBenchmarks.class.getResourceAsStream(jsonResource);
    try {
      return Okio.buffer(Okio.source(stream)).readByteArray();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}

