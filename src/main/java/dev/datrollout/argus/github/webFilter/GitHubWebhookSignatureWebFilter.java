package dev.datrollout.argus.github.webFilter;

import dev.datrollout.argus.github.client.GitHubAppProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
@Slf4j
public class GitHubWebhookSignatureWebFilter implements WebFilter {

    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String WEBHOOK_PATH_PREFIX = "/webhook";
    private final GitHubAppProperties gitHubAppProperties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        if (!request.getPath().value().startsWith(WEBHOOK_PATH_PREFIX)) {
            return chain.filter(exchange);
        }

        String signature = request.getHeaders().getFirst(SIGNATURE_HEADER);
        if (signature == null || !signature.startsWith("sha256=")) {
            log.warn(
                    "[Webhook] Rejected request from {} — missing or malformed {} header",
                    request.getRemoteAddress(),
                    SIGNATURE_HEADER);
            return reject(exchange, "Missing or malformed " + SIGNATURE_HEADER);
        }

        return DataBufferUtils.join(request.getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(dataBuffer -> {
                    byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bodyBytes);
                    DataBufferUtils.release(dataBuffer);

                    String expected = "sha256=" + computeHmacHex(bodyBytes, gitHubAppProperties.getSecret());
                    if (!constantTimeEquals(expected, signature)) {
                        log.warn(
                                "[Webhook] Rejected request from {} — signature mismatch (path={})",
                                request.getRemoteAddress(),
                                request.getPath().value());
                        return reject(exchange, "Signature mismatch");
                    }
                    log.debug(
                            "[Webhook] Valid signature accepted for path={}",
                            request.getPath().value());
                    ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            DataBuffer buffer =
                                    exchange.getResponse().bufferFactory().wrap(bodyBytes);
                            return Flux.just(buffer);
                        }
                    };

                    return chain.filter(
                            exchange.mutate().request(mutatedRequest).build());
                });
    }

    private String computeHmacHex(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(payload);
            return HexFormat.of().formatHex(hmacBytes); // Java 17+
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }

    // Constant-time comparison to prevent timing attacks
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private Mono<Void> reject(ServerWebExchange exchange, String reason) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        byte[] body = reason.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
