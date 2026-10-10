package cn.lunadeer.reColorfulMap.services.download;

import cn.lunadeer.reColorfulMap.Configuration;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Streaming download with a byte ceiling, an idle timeout and a deadline shared by all redirects. */
public final class HttpImageDownloader {
    // Reuse connection pools. Bound the cache even if an administrator repeatedly reloads timeouts.
    private static final Map<Integer, HttpClient> CLIENTS = new LinkedHashMap<>(4, 0.75f, true);

    private HttpImageDownloader() {
    }

    public record Download(Path path) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }

    public static Download download(URI original, UrlWhitelist whitelist,
                                    Configuration.DownloadSettings limits) throws IOException {
        return download(original, whitelist, limits, null);
    }

    // A directory override lets tests prove cancellation and failures leave no download files.
    static Download download(URI original, UrlWhitelist whitelist,
                             Configuration.DownloadSettings limits, Path temporaryDirectory) throws IOException {
        validateLimits(limits);
        checkInterrupted();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.totalTimeoutMillis());
        Path file = temporaryDirectory == null ? Files.createTempFile("recolorfulmap-download-", ".tmp")
                : Files.createTempFile(temporaryDirectory, "recolorfulmap-download-", ".tmp");
        boolean transferred = false;
        try {
            URI current = original;
            HttpClient client = client(limits.connectTimeoutMillis());
            for (int redirects = 0; ; redirects++) {
                checkInterrupted();
                try {
                    current = UrlWhitelist.normalize(current.toString());
                    if (!whitelist.allows(current)) {
                        throw new IOException("Image URL is not allowed: " + current);
                    }
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Invalid image URL", exception);
                }
                long remaining = remaining(deadline);
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofNanos(remaining))
                        .header("User-Agent", "ReColorfulMap")
                        .GET().build();
                BoundedBody body = new BoundedBody(file, limits.maxBytes());
                CompletableFuture<HttpResponse<Path>> response = null;
                HttpResponse<Path> result;
                try {
                    response = client.sendAsync(request, info -> {
                        body.progress = System.nanoTime();
                        if (info.statusCode() != 200) {
                            return new IgnoredBody(file);
                        }
                        long advertised = info.headers().firstValueAsLong("Content-Length").orElse(-1);
                        if (advertised > limits.maxBytes()) {
                            body.fail(new IOException("Image download exceeds the byte limit of " + limits.maxBytes()));
                        }
                        return body;
                    });
                    result = await(response, body, deadline, limits.readTimeoutMillis());
                } finally {
                    if (response != null) {
                        response.cancel(true);
                    }
                    body.close();
                }
                int status = result.statusCode();
                if (status == 200) {
                    transferred = true;
                    return new Download(file);
                }
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                    throw new IOException("Image server returned HTTP " + status);
                }
                if (redirects >= limits.maxRedirects()) {
                    throw new IOException("Image download exceeded the redirect limit of " + limits.maxRedirects());
                }
                String location = result.headers().firstValue("Location")
                        .orElseThrow(() -> new IOException("Image server redirect has no Location"));
                try {
                    current = current.resolve(location);
                } catch (IllegalArgumentException exception) {
                    throw new IOException("Image server returned an invalid redirect", exception);
                }
            }
        } finally {
            if (!transferred) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static HttpResponse<Path> await(CompletableFuture<HttpResponse<Path>> response, BoundedBody body,
                                             long deadline, int readTimeoutMillis) throws IOException {
        long idleLimit = TimeUnit.MILLISECONDS.toNanos(readTimeoutMillis);
        try {
            while (true) {
                long wait = Math.min(remaining(deadline), idleLimit - (System.nanoTime() - body.progress));
                if (wait <= 0) {
                    throw new IOException("Image download timed out while waiting for data");
                }
                try {
                    return response.get(wait, TimeUnit.NANOSECONDS);
                } catch (TimeoutException ignored) {
                    // Recheck both clocks: continuously trickling bytes must not extend the total deadline.
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Image download was cancelled");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Image download failed", cause);
        }
    }

    private static long remaining(long deadline) throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new IOException("Image download exceeded its total timeout");
        }
        return remaining;
    }

    public static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Image generation was cancelled");
        }
    }

    private static void validateLimits(Configuration.DownloadSettings limits) {
        if (limits.maxBytes() <= 0 || limits.maxPixels() <= 0 || limits.connectTimeoutMillis() <= 0
                || limits.readTimeoutMillis() <= 0 || limits.totalTimeoutMillis() <= 0 || limits.maxRedirects() < 0) {
            throw new IllegalArgumentException("Image download limits must be positive (redirects may be zero)");
        }
    }

    private static synchronized HttpClient client(int connectTimeoutMillis) {
        HttpClient client = CLIENTS.get(connectTimeoutMillis);
        if (client == null) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            if (CLIENTS.size() == 4) {
                CLIENTS.remove(CLIENTS.keySet().iterator().next());
            }
            CLIENTS.put(connectTimeoutMillis, client);
        }
        return client;
    }

    /** Do not consume an unbounded error or redirect body while waiting to inspect its headers. */
    private static final class IgnoredBody implements HttpResponse.BodySubscriber<Path> {
        private final CompletableFuture<Path> result;

        private IgnoredBody(Path path) {
            result = CompletableFuture.completedFuture(path);
        }

        @Override public CompletionStage<Path> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
        @Override public void onNext(List<ByteBuffer> item) { }
        @Override public void onError(Throwable throwable) { }
        @Override public void onComplete() { }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<Path>, AutoCloseable {
        private final Path file;
        private final long limit;
        private final CompletableFuture<Path> result = new CompletableFuture<>();
        private FileChannel output;
        private Flow.Subscription subscription;
        private long count;
        private boolean closed;
        private volatile long progress = System.nanoTime();

        private BoundedBody(Path file, long limit) throws IOException {
            this.file = file;
            this.limit = limit;
            this.output = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        @Override public CompletionStage<Path> getBody() { return result; }

        @Override public synchronized void onSubscribe(Flow.Subscription subscription) {
            if (closed || this.subscription != null || result.isDone()) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (closed || result.isDone()) {
                return;
            }
            try {
                for (ByteBuffer buffer : buffers) {
                    int bytes = buffer.remaining();
                    if (bytes > limit - count) {
                        throw new IOException("Image download exceeds the byte limit of " + limit);
                    }
                    count += bytes;
                    while (buffer.hasRemaining()) {
                        output.write(buffer);
                    }
                    if (bytes > 0) {
                        progress = System.nanoTime();
                    }
                }
                subscription.request(1);
            } catch (IOException exception) {
                fail(exception);
            }
        }

        @Override public synchronized void onError(Throwable throwable) { fail(throwable); }

        @Override public synchronized void onComplete() {
            if (!closed && !result.isDone()) {
                try {
                    output.close();
                    output = null;
                    result.complete(file);
                } catch (IOException exception) {
                    fail(exception);
                }
            }
        }

        private synchronized void fail(Throwable throwable) {
            result.completeExceptionally(throwable);
            close();
        }

        @Override public synchronized void close() {
            closed = true;
            result.completeExceptionally(new IOException("Image download was closed"));
            if (subscription != null) {
                subscription.cancel();
            }
            if (output != null) {
                try {
                    output.close();
                } catch (IOException exception) {
                    result.completeExceptionally(exception);
                }
                output = null;
            }
        }
    }
}
