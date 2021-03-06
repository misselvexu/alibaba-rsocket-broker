package com.alibaba.rsocket.loadbalance;

import com.alibaba.rsocket.RSocketRequesterSupport;
import com.alibaba.rsocket.cloudevents.CloudEventRSocket;
import com.alibaba.rsocket.events.ServicesExposedEvent;
import com.alibaba.rsocket.health.RSocketServiceHealth;
import com.alibaba.rsocket.metadata.GSVRoutingMetadata;
import com.alibaba.rsocket.metadata.MessageMimeTypeMetadata;
import com.alibaba.rsocket.metadata.RSocketCompositeMetadata;
import com.alibaba.rsocket.metadata.RSocketMimeType;
import com.alibaba.rsocket.observability.RsocketErrorCode;
import io.cloudevents.v1.CloudEventImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import io.rsocket.AbstractRSocket;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.exceptions.ConnectionErrorException;
import io.rsocket.exceptions.SetupException;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.plugins.RSocketInterceptor;
import io.rsocket.uri.UriTransportRegistry;
import io.rsocket.util.ByteBufPayload;
import org.jetbrains.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

/**
 * Load balanced RSocket:  how to remove failure nodes?
 *
 * @author leijuan
 */
public class LoadBalancedRSocket extends AbstractRSocket implements CloudEventRSocket {
    private RandomSelector<RSocket> randomSelector;
    private Logger log = LoggerFactory.getLogger(LoadBalancedRSocket.class);
    private String serviceId;
    private Flux<Collection<String>> urisFactory;
    private Collection<String> lastRSocketUris = new ArrayList<>();
    private Map<String, RSocket> activeSockets;
    /**
     * unhealthy uris
     */
    private Set<String> unHealthyUriSet = new HashSet<>();
    private long lastHealthCheckTimeStamp = System.currentTimeMillis();
    private long lastRefreshTimeStamp = System.currentTimeMillis();
    /**
     * health check interval seconds
     */
    private static int HEALTH_CHECK_INTERVAL_SECONDS = 15;
    /**
     * retry count because of connection error and interval is 5 seconds
     */
    private int retryCount = 12;
    private RSocketRequesterSupport requesterSupport;
    private ByteBuf healthCheckCompositeByteBuf;

    public Set<String> getUnHealthyUriSet() {
        return unHealthyUriSet;
    }

    public Collection<String> getLastRSocketUris() {
        return lastRSocketUris;
    }

    public long getLastHealthCheckTimeStamp() {
        return lastHealthCheckTimeStamp;
    }

    public long getLastRefreshTimeStamp() {
        return lastRefreshTimeStamp;
    }

    /**
     * connection error predicate
     */
    private static Predicate<? super Throwable> CONNECTION_ERROR_PREDICATE = e -> e instanceof ClosedChannelException || e instanceof ConnectionErrorException;

    public LoadBalancedRSocket(String serviceId, Flux<Collection<String>> urisFactory,
                               RSocketRequesterSupport requesterSupport) {
        this.serviceId = serviceId;
        this.randomSelector = new RandomSelector<>(this.serviceId, new ArrayList<>());
        this.urisFactory = urisFactory;
        this.requesterSupport = requesterSupport;
        this.activeSockets = new HashMap<>();
        this.urisFactory.subscribe(this::refreshRsockets);
        //composite metadata for health check
        RSocketCompositeMetadata compositeMetadata = RSocketCompositeMetadata.from(
                new GSVRoutingMetadata(null, RSocketServiceHealth.class.getCanonicalName(), "check", null),
                new MessageMimeTypeMetadata(RSocketMimeType.Hessian));
        ByteBuf compositeMetadataContent = compositeMetadata.getContent();
        this.healthCheckCompositeByteBuf = compositeMetadataContent.copy();
        ReferenceCountUtil.safeRelease(compositeMetadataContent);
        //start health check timer
        startHealthCheckTimer();
    }

    private void refreshRsockets(Collection<String> rsocketUris) {
        //no changes
        if (isSameWithLastUris(rsocketUris)) {
            return;
        }
        this.lastRefreshTimeStamp = System.currentTimeMillis();
        this.lastRSocketUris = rsocketUris;
        this.unHealthyUriSet.clear();
        Flux.fromIterable(rsocketUris)
                .flatMap(rsocketUri -> {
                    if (activeSockets.containsKey(rsocketUri)) {
                        return Mono.just(Tuples.of(rsocketUri, activeSockets.get(rsocketUri)));
                    } else {
                        return connect(rsocketUri)
                                //health check after connection
                                .flatMap(rsocket -> healthCheck(rsocket).map(payload -> Tuples.of(rsocketUri, rsocket)))
                                .doOnError(error -> {
                                    log.error(RsocketErrorCode.message("RST-400500", rsocketUri), error);
                                    tryToReconnect(rsocketUri, error);
                                });
                    }
                })
                .collectList()
                .subscribe(tupleRsockets -> {
                    if (tupleRsockets.isEmpty()) return;
                    Map<String, RSocket> newActiveRSockets = new HashMap<>();
                    for (Tuple2<String, RSocket> tuple : tupleRsockets) {
                        newActiveRSockets.put(tuple.getT1(), tuple.getT2());
                    }
                    @SuppressWarnings("DuplicatedCode")
                    Map<String, RSocket> staleRSockets = new HashMap<>();
                    //get all stale rsockets
                    for (Map.Entry<String, RSocket> entry : activeSockets.entrySet()) {
                        if (!newActiveRSockets.containsKey(entry.getKey())) {
                            staleRSockets.put(entry.getKey(), entry.getValue());
                        }
                    }
                    Map<String, RSocket> newAddedRSockets = new HashMap<>();
                    //get all new added rsockets
                    for (Map.Entry<String, RSocket> entry : newActiveRSockets.entrySet()) {
                        if (!activeSockets.containsKey(entry.getKey())) {
                            newAddedRSockets.put(entry.getKey(), entry.getValue());
                        }
                    }
                    this.activeSockets = newActiveRSockets;
                    this.randomSelector = new RandomSelector<>(this.serviceId, new ArrayList<>(activeSockets.values()));
                    //close all stale rsocket after 45 seconds for drain mode
                    if (!staleRSockets.isEmpty()) {
                        Flux.fromIterable(staleRSockets.entrySet())
                                .delaySubscription(Duration.ofSeconds(45))
                                .subscribe(entry -> {
                                    log.info(RsocketErrorCode.message("RST-200011", entry.getKey()));
                                    entry.getValue().dispose();
                                });
                    }
                    //subscribe rsocket close event
                    for (Map.Entry<String, RSocket> entry : newAddedRSockets.entrySet()) {
                        entry.getValue().onClose().subscribe(aVoid -> {
                            onRSocketClosed(entry.getKey(), entry.getValue(), null);
                        });
                    }
                });
    }

    @Override
    public Mono<Payload> requestResponse(Payload payload) {
        RSocket next = randomSelector.next();
        if (next == null) {
            return Mono.error(new NoAvailableConnectionException(RsocketErrorCode.message("RST-200404", serviceId)));
        }
        return next.requestResponse(payload)
                .onErrorResume(CONNECTION_ERROR_PREDICATE, error -> {
                    onRSocketClosed(next, error);
                    return requestResponse(payload);
                });
    }


    @Override
    public Mono<Void> fireAndForget(Payload payload) {
        RSocket next = randomSelector.next();
        if (next == null) {
            return Mono.error(new NoAvailableConnectionException(RsocketErrorCode.message("RST-200404", serviceId)));
        }
        return next.fireAndForget(payload)
                .onErrorResume(CONNECTION_ERROR_PREDICATE, error -> {
                    onRSocketClosed(next, error);
                    return fireAndForget(payload);
                });
    }

    @Override
    public Mono<Void> fireCloudEvent(CloudEventImpl<?> cloudEvent) {
        try {
            Payload payload = cloudEventToMetadataPushPayload(cloudEvent);
            return metadataPush(payload);
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    /**
     * fire cloud event to upstream active rsockets
     *
     * @param cloudEvent cloud event
     * @return void
     */
    public Mono<Void> fireCloudEventToUpstreamAll(CloudEventImpl<?> cloudEvent) {
        try {
            return Flux.fromIterable(this.getActiveSockets().values())
                    .flatMap(rsocket -> rsocket.metadataPush(cloudEventToMetadataPushPayload(cloudEvent)))
                    .doOnError(throwable -> log.error("Failed to fire event to upstream", throwable))
                    .then();
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    @Override
    public Flux<Payload> requestStream(Payload payload) {
        RSocket next = randomSelector.next();
        if (next == null) {
            return Flux.error(new NoAvailableConnectionException(RsocketErrorCode.message("RST-200404", serviceId)));
        }
        return next.requestStream(payload)
                .onErrorResume(CONNECTION_ERROR_PREDICATE, error -> {
                    onRSocketClosed(next, error);
                    return requestStream(payload);
                });
    }

    @Override
    public Flux<Payload> requestChannel(Publisher<Payload> payloads) {
        RSocket next = randomSelector.next();
        if (next == null) {
            return Flux.error(new NoAvailableConnectionException(RsocketErrorCode.message("RST-200404", serviceId)));
        }
        return next.requestChannel(payloads)
                .onErrorResume(CONNECTION_ERROR_PREDICATE, error -> {
                    onRSocketClosed(next, error);
                    return requestChannel(payloads);
                });
    }

    @Override
    public Mono<Void> metadataPush(final Payload payload) {
        return Flux.fromIterable(activeSockets.values()).flatMap(rSocket -> rSocket.metadataPush(payload)).then();
    }

    public void dispose() {
        synchronized (this) {
            super.dispose();
            Flux.fromIterable(activeSockets.values())
                    .doOnTerminate(() -> activeSockets.clear())
                    .subscribe(Disposable::dispose);
        }
        ReferenceCountUtil.release(this.healthCheckCompositeByteBuf);
    }

    public Map<String, RSocket> getActiveSockets() {
        return activeSockets;
    }

    public void refreshUnHealthyUris() {
        for (String unHealthyUri : unHealthyUriSet) {
            tryToReconnect(unHealthyUri, null);
        }
    }

    public void onRSocketClosed(RSocket rsocket, @Nullable Throwable cause) {
        for (Map.Entry<String, RSocket> entry : activeSockets.entrySet()) {
            if (entry.getValue() == rsocket) {
                onRSocketClosed(entry.getKey(), entry.getValue(), null);
            }
        }
        if (!rsocket.isDisposed()) {
            try {
                rsocket.dispose();
            } catch (Exception ignore) {

            }
        }
    }

    public void onRSocketClosed(String uri, @Nullable Throwable cause) {
        if (activeSockets.containsKey(uri)) {
            onRSocketClosed(uri, activeSockets.get(uri), cause);
        }
    }

    public void onRSocketClosed(String rsocketUri, RSocket rsocket, @Nullable Throwable cause) {
        //in last rsocket uris or not
        if (this.lastRSocketUris.contains(rsocketUri)) {
            this.unHealthyUriSet.add(rsocketUri);
            if (activeSockets.containsKey(rsocketUri)) {
                activeSockets.remove(rsocketUri);
                this.randomSelector = new RandomSelector<>(this.serviceId, new ArrayList<>(activeSockets.values()));
                log.error(RsocketErrorCode.message("RST-500407", rsocketUri));
                tryToReconnect(rsocketUri, cause);
            }
            if (!rsocket.isDisposed()) {
                try {
                    rsocket.dispose();
                } catch (Exception ignore) {

                }
            }
        }
    }

    public void onRSocketReconnected(String rsocketUri, RSocket rsocket) {
        this.activeSockets.put(rsocketUri, rsocket);
        this.unHealthyUriSet.remove(rsocketUri);
        this.randomSelector = new RandomSelector<>(this.serviceId, new ArrayList<>(activeSockets.values()));
        rsocket.onClose().subscribe(aVoid -> onRSocketClosed(rsocketUri, rsocket, null));
        CloudEventImpl<ServicesExposedEvent> cloudEvent = requesterSupport.servicesExposedEvent().get();
        if (cloudEvent != null) {
            try {
                Payload payload = cloudEventToMetadataPushPayload(cloudEvent);
                rsocket.metadataPush(payload).subscribe();
            } catch (Exception ignore) {

            }
        }
    }

    public void tryToReconnect(String rsocketUri, @Nullable Throwable error) {
        //try to reconnect every 5 seconds in 1 minute if ClosedChannelException
        if (error instanceof ClosedChannelException) {
            Flux.range(1, retryCount)
                    .delayElements(Duration.ofSeconds(5))
                    .filter(id -> activeSockets.isEmpty() || !activeSockets.containsKey(rsocketUri))
                    .subscribe(number -> {
                        connect(rsocketUri)
                                .flatMap(rsocket -> healthCheck(rsocket).map(payload -> rsocket))
                                .doOnError(e -> {
                                    this.getUnHealthyUriSet().add(rsocketUri);
                                    log.error(RsocketErrorCode.message("RST-500408", number, rsocketUri), e);
                                })
                                .subscribe(rsocket -> {
                                    onRSocketReconnected(rsocketUri, rsocket);
                                    log.info(RsocketErrorCode.message("RST-500203", rsocketUri));
                                });
                    });
        }
    }

    Mono<RSocket> connect(String uri) {
        try {
            RSocketFactory.ClientRSocketFactory clientRSocketFactory = RSocketFactory.connect();
            for (RSocketInterceptor requestInterceptor : requesterSupport.requestInterceptors()) {
                clientRSocketFactory = clientRSocketFactory.addRequesterPlugin(requestInterceptor);
            }
            for (RSocketInterceptor responderInterceptor : requesterSupport.responderInterceptors()) {
                clientRSocketFactory = clientRSocketFactory.addResponderPlugin(responderInterceptor);
            }
            return clientRSocketFactory
                    .keepAliveMissedAcks(12)
                    .setupPayload(requesterSupport.setupPayload().get())
                    .metadataMimeType(RSocketMimeType.CompositeMetadata.getType())
                    .dataMimeType(RSocketMimeType.Hessian.getType())
                    .errorConsumer(error -> {
                        log.error(error.getMessage(), error);
                    })
                    .frameDecoder(PayloadDecoder.ZERO_COPY)
                    .acceptor(requesterSupport.socketAcceptor())
                    .transport(UriTransportRegistry.clientForUri(uri))
                    .start();
        } catch (Exception e) {
            log.error(RsocketErrorCode.message("RST-400500", uri), e);
            return Mono.error(new ConnectionErrorException(uri));
        }
    }

    /**
     * start health check timer: check the connection every 15 seconds
     * please check https://github.com/alibaba/alibaba-rsocket-broker/issues/10
     */
    public void startHealthCheckTimer() {
        this.lastHealthCheckTimeStamp = System.currentTimeMillis();
        Flux.interval(Duration.ofSeconds(HEALTH_CHECK_INTERVAL_SECONDS))
                .flatMap(timestamp -> Flux.fromIterable(activeSockets.entrySet()))
                .subscribe(entry -> {
                    healthCheck(entry.getValue()).doOnError(error -> {
                        if (error instanceof ClosedChannelException || error instanceof SetupException) { //connection closed
                            onRSocketClosed(entry.getKey(), entry.getValue(), error);
                        }
                    }).subscribe();
                });
    }

    private Mono<Payload> healthCheck(RSocket rsocket) {
        return rsocket.requestResponse(ByteBufPayload.create(Unpooled.EMPTY_BUFFER, this.healthCheckCompositeByteBuf.retainedDuplicate()));
    }

    public boolean isSameWithLastUris(Collection<String> newRSocketUris) {
        return this.lastRSocketUris.size() == newRSocketUris.size() && this.lastRSocketUris.containsAll(newRSocketUris) && newRSocketUris.containsAll(this.lastRSocketUris);
    }

}
