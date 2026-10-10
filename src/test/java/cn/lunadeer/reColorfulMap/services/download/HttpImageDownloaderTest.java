package cn.lunadeer.reColorfulMap.services.download;

import cn.lunadeer.reColorfulMap.Configuration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpImageDownloaderTest {
    @TempDir Path temporary;
    private static final UrlWhitelist UNRESTRICTED = UrlWhitelist.compile(List.of());

    private static Configuration.DownloadSettings limits(long bytes, int readTimeout, int totalTimeout, int redirects) {
        return new Configuration.DownloadSettings(bytes, 16_000_000, 1000, readTimeout, totalTimeout, redirects);
    }

    @Test
    void streamsAnExactlySizedBodyAndDeletesItWhenClosed() throws Exception {
        byte[] expected = new byte[1024];
        expected[999] = 42;
        try (Server server = new Server()) {
            server.route("/image", exchange -> send(exchange, 200, expected, false));
            try (var download = get(server.url("/image"), UNRESTRICTED, limits(1024, 1000, 3000, 5))) {
                assertArrayEquals(expected, Files.readAllBytes(download.path()));
            }
        }
        assertNoFiles();
    }

    @Test
    void enforcesActualChunkedBytesWithoutContentLength() throws Exception {
        try (Server server = new Server()) {
            server.route("/image", exchange -> send(exchange, 200, new byte[8192], true));
            IOException failure = assertThrows(IOException.class,
                    () -> get(server.url("/image"), UNRESTRICTED, limits(1024, 1000, 3000, 5)));
            assertTrue(failure.getMessage().contains("byte limit"));
        }
        assertNoFiles();
    }

    @Test
    void rejectsExcessiveAdvertisedLengthBeforeWaitingForTheBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Server server = new Server()) {
            server.route("/image", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 1_000_000);
                    await(release);
                }
            });
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                IOException failure = assertThrows(IOException.class,
                        () -> get(server.url("/image"), UNRESTRICTED, limits(1024, 5000, 10000, 5)));
                assertTrue(failure.getMessage().contains("byte limit"));
            });
        } finally {
            release.countDown();
        }
        assertNoFiles();
    }

    @Test
    void validatesEveryRedirectBeforeOpeningItsDestination() throws Exception {
        AtomicInteger forbiddenHits = new AtomicInteger();
        try (Server server = new Server()) {
            server.route("/allowed", exchange -> redirect(exchange, "/outside/image"));
            server.route("/outside", exchange -> {
                forbiddenHits.incrementAndGet();
                send(exchange, 200, new byte[]{1}, false);
            });
            UrlWhitelist policy = UrlWhitelist.compile(List.of(server.url("/allowed").toString()));
            IOException failure = assertThrows(IOException.class,
                    () -> get(server.url("/allowed"), policy, limits(1024, 1000, 3000, 5)));
            assertTrue(failure.getMessage().contains("not allowed"));
            assertEquals(0, forbiddenHits.get());
        }
        assertNoFiles();
    }

    @Test
    void preventsCrossHostAndCrossPortRedirects() throws Exception {
        AtomicInteger forbiddenHits = new AtomicInteger();
        try (Server initial = new Server(); Server other = new Server()) {
            other.route("/image", exchange -> {
                forbiddenHits.incrementAndGet();
                send(exchange, 200, new byte[]{1}, false);
            });
            initial.route("/port", exchange -> redirect(exchange, other.url("/image").toString()));
            initial.route("/host", exchange -> redirect(exchange,
                    initial.url("/image").toString().replace("127.0.0.1", "localhost")));
            initial.route("/image", exchange -> {
                forbiddenHits.incrementAndGet();
                send(exchange, 200, new byte[]{1}, false);
            });
            UrlWhitelist policy = UrlWhitelist.compile(List.of(initial.url("/").toString()));
            for (String route : List.of("/port", "/host")) {
                assertThrows(IOException.class, () -> get(initial.url(route), policy, limits(1024, 1000, 3000, 5)));
            }
            assertEquals(0, forbiddenHits.get());
        }
        assertNoFiles();
    }

    @Test
    void followsAllowedRelativeRedirectsWithoutReadingTheirBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Server server = new Server()) {
            server.route("/first", exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().set("Location", "/final");
                    exchange.sendResponseHeaders(302, 1_000_000);
                    await(release);
                }
            });
            server.route("/final", exchange -> send(exchange, 200, new byte[]{1, 2, 3}, false));
            UrlWhitelist policy = UrlWhitelist.compile(List.of(server.url("/").toString()));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                try (var download = get(server.url("/first"), policy, limits(1024, 5000, 10000, 5))) {
                    assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(download.path()));
                }
            });
        } finally {
            release.countDown();
        }
        assertNoFiles();
    }

    @Test
    void limitsRedirectLoops() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (Server server = new Server()) {
            server.route("/loop", exchange -> {
                requests.incrementAndGet();
                redirect(exchange, "/loop");
            });
            IOException failure = assertThrows(IOException.class,
                    () -> get(server.url("/loop"), UNRESTRICTED, limits(1024, 1000, 3000, 2)));
            assertTrue(failure.getMessage().contains("redirect limit"));
            assertEquals(3, requests.get());
        }
        assertNoFiles();
    }

    @Test
    void totalDeadlineIsSharedAcrossRedirects() throws Exception {
        try (Server server = new Server()) {
            server.route("/loop", exchange -> {
                pause(180);
                redirect(exchange, "/loop");
            });
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IOException.class,
                    () -> get(server.url("/loop"), UNRESTRICTED, limits(1024, 2000, 450, 10))));
        }
        assertNoFiles();
    }

    @Test
    void totalDeadlineStopsAContinuouslyTricklingBody() throws Exception {
        AtomicInteger sent = new AtomicInteger();
        try (Server server = new Server()) {
            server.route("/slow", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    for (int i = 0; i < 200; i++) {
                        exchange.getResponseBody().write(i);
                        exchange.getResponseBody().flush();
                        sent.incrementAndGet();
                        pause(20);
                    }
                }
            });
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IOException.class,
                    () -> get(server.url("/slow"), UNRESTRICTED, limits(1024, 250, 500, 5))));
            assertTrue(sent.get() >= 3, "The body must have made repeated progress before the total timeout");
        }
        assertNoFiles();
    }

    @Test
    void idleTimeoutStopsAStalledBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Server server = new Server()) {
            server.route("/stalled", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(1);
                    exchange.getResponseBody().flush();
                    await(release);
                }
            });
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                IOException failure = assertThrows(IOException.class,
                        () -> get(server.url("/stalled"), UNRESTRICTED, limits(1024, 150, 5000, 5)));
                assertTrue(failure.getMessage().contains("waiting for data"));
            });
        } finally {
            release.countDown();
        }
        assertNoFiles();
    }

    @Test
    void idleTimeoutAlsoBoundsWaitingForResponseHeaders() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Server server = new Server()) {
            server.route("/headers", exchange -> {
                try (exchange) {
                    await(release);
                }
            });
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IOException.class,
                    () -> get(server.url("/headers"), UNRESTRICTED, limits(1024, 150, 5000, 5))));
        } finally {
            release.countDown();
        }
        assertNoFiles();
    }

    @Test
    void interruptCancelsAnInFlightRequestAndRemovesItsTemporaryFile() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        try (Server server = new Server()) {
            server.route("/cancel", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(1);
                    exchange.getResponseBody().flush();
                    started.countDown();
                    await(release);
                }
            });
            Thread worker = new Thread(() -> {
                try (var ignored = get(server.url("/cancel"), UNRESTRICTED, limits(1024, 10000, 20000, 5))) {
                    failure.set(new AssertionError("Cancelled request completed successfully"));
                } catch (Throwable exception) {
                    failure.set(exception);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            worker.start();
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
                worker.interrupt();
                worker.join(2000);
                assertFalse(worker.isAlive());
                assertInstanceOf(InterruptedIOException.class, failure.get());
                assertTrue(interrupted.get());
            } finally {
                release.countDown();
                worker.interrupt();
                worker.join(2000);
            }
        }
        assertNoFiles();
    }

    @Test
    void preInterruptedRequestsDoNotCreateFilesOrOpenNetwork() throws Exception {
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class,
                    () -> get(URI.create("http://127.0.0.1:1/image"), UNRESTRICTED, limits(1024, 1000, 3000, 5)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertNoFiles();
    }

    @Test
    void rejectsErrorResponsesWithoutConsumingTheirBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Server server = new Server()) {
            server.route("/error", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(503, 1_000_000);
                    await(release);
                }
            });
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                IOException failure = assertThrows(IOException.class,
                        () -> get(server.url("/error"), UNRESTRICTED, limits(1024, 5000, 10000, 5)));
                assertTrue(failure.getMessage().contains("HTTP 503"));
            });
        } finally {
            release.countDown();
        }
        assertNoFiles();
    }

    private HttpImageDownloader.Download get(URI uri, UrlWhitelist whitelist,
                                             Configuration.DownloadSettings settings) throws IOException {
        return HttpImageDownloader.download(uri, whitelist, settings, temporary);
    }

    private void assertNoFiles() throws IOException {
        try (var paths = Files.list(temporary)) {
            assertEquals(0, paths.count(), "Failed or closed downloads must not leave temporary files");
        }
    }

    private static void send(HttpExchange exchange, int status, byte[] bytes, boolean chunked) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(status, chunked ? 0 : bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void redirect(HttpExchange exchange, String target) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Location", target);
            exchange.sendResponseHeaders(302, -1);
        }
    }

    private static void pause(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    private static final class Server implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();

        private Server() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.start();
        }

        private void route(String path, HttpHandler handler) {
            server.createContext(path, exchange -> {
                try {
                    handler.handle(exchange);
                } catch (IOException ignored) {
                    // Cancellation intentionally closes the client connection while a handler is writing.
                    exchange.close();
                }
            });
        }

        private URI url(String path) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        }

        @Override public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
