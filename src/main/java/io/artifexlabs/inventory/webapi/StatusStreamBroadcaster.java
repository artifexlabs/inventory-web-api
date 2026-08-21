/*
 * @formatter:off
 * Copyright © 2019 admin (admin@artifexlabs.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * @formatter:on
 */
package io.artifexlabs.inventory.webapi;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import io.artifexlabs.inventory.api.events.StatusEvent;
import io.artifexlabs.inventory.api.events.StatusEvents;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

/**
 * Fans the {@code status.events} bus topic out to connected browsers and
 * apps (MORE_VERTX ask 3). The gateway subscribes to the bus ONCE and
 * distributes per connection — the event bus itself is never exposed to a
 * client, because bus membership is access (VERTICLES.md).
 *
 * <p>
 * <b>Scoping is a security boundary.</b> A subscriber sees only:
 * <ul>
 * <li>events whose {@code actor} is that user, and</li>
 * <li>if the user is an admin, unattributed system events (a printer that
 * cannot be reached belongs to whoever runs the system, not to a user), and
 * everything when they explicitly ask for the firehose.</li>
 * </ul>
 *
 * <p>
 * Delivery is live plus SHALLOW replay: a bounded, time-limited ring lets a
 * client that reconnects with {@code Last-Event-ID} catch the gap it missed.
 * This is deliberately not durable and not guaranteed — the audit trail is
 * the record; this is the doorbell.
 */
@ApplicationScoped
public class StatusStreamBroadcaster {
  private final static org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StatusStreamBroadcaster.class);

  /** How many recent events a reconnecting client can catch up on. */
  final static int RING_SIZE = 100;
  /** How far back that catch-up reaches, whichever limit binds first. */
  final static Duration RING_AGE = Duration.ofMinutes(15);

  private final Set<Subscriber> subscribers = new CopyOnWriteArraySet<>();
  private final Deque<StatusEvent> ring = new ArrayDeque<>();

  @Inject
  Vertx vertx;

  /**
   * One connected client and the scope it may observe. Deliberately NOT
   * typed to the SSE API: the scoping rules are a security boundary and must
   * be testable without a servlet container.
   */
  final static class Subscriber {
    private final java.util.function.Consumer<StatusEvent> sender;
    private final java.util.function.BooleanSupplier closed;
    private final String userId;
    private final boolean admin;
    private final boolean firehose;

    Subscriber(java.util.function.Consumer<StatusEvent> sender, java.util.function.BooleanSupplier closed,
        String userId, boolean admin, boolean firehose) {
      this.sender = sender;
      this.closed = closed;
      this.userId = userId;
      this.admin = admin;
      this.firehose = firehose;
    }

    boolean maySee(StatusEvent event) {
      if (this.admin && this.firehose)
        return true;
      String actor = event.actor();
      if (actor != null)
        return actor.equals(this.userId);
      // unattributed: system trouble, for whoever runs the system
      return this.admin;
    }

    private void send(StatusEvent event) {
      this.sender.accept(event);
    }
  }

  /** The SSE wire shape: severity is the event NAME, the JSON body the data. */
  private static OutboundSseEvent toSse(Sse sse, StatusEvent event) {
    return sse.newEventBuilder()
        .id(event.id())
        .name(event.severity().name().toLowerCase(java.util.Locale.ROOT))
        .mediaType(MediaType.APPLICATION_JSON_TYPE)
        .data(String.class, StatusEvents.toWire(event).encode())
        .build();
  }

  /** Subscribe to the bus once, at startup, for the whole gateway. */
  void onStart(@Observes StartupEvent ignored) {
    this.vertx.eventBus().<JsonObject>consumer(StatusEvents.ADDRESS, message -> {
      try {
        accept(StatusEvents.fromWire(message.body()));
      } catch (RuntimeException e) {
        log.warn("undecodable status event: {}", e.toString());
      }
    });
    log.info("status stream: subscribed to {} (ring {} events / {})", StatusEvents.ADDRESS, RING_SIZE, RING_AGE);
  }

  /** Remember an event for replay, then push it to everyone entitled to see it. */
  void accept(StatusEvent event) {
    remember(event);
    for (Subscriber s : this.subscribers) {
      if (!s.maySee(event))
        continue;
      try {
        if (s.closed.getAsBoolean())
          this.subscribers.remove(s);
        else
          s.send(event);
      } catch (RuntimeException e) {
        // a dead connection must never break the fan-out for everyone else
        this.subscribers.remove(s);
      }
    }
  }

  private synchronized void remember(StatusEvent event) {
    this.ring.addLast(event);
    Instant cutoff = Instant.now().minus(RING_AGE);
    while (this.ring.size() > RING_SIZE
        || (!this.ring.isEmpty() && this.ring.peekFirst().ts() != null && this.ring.peekFirst().ts().isBefore(cutoff)))
      this.ring.removeFirst();
  }

  /** Everything after {@code lastEventId} that this scope may see, oldest first. */
  private synchronized List<StatusEvent> replay(Subscriber subscriber, String lastEventId) {
    List<StatusEvent> all = new ArrayList<>(this.ring);
    if (lastEventId == null || lastEventId.isBlank())
      return List.of();
    int from = -1;
    for (int i = 0; i < all.size(); i++)
      if (lastEventId.equals(all.get(i).id()))
        from = i;
    if (from < 0)
      // the gap is older than the ring: replaying an arbitrary window would
      // be a lie about completeness, so send nothing and let live resume
      return List.of();
    List<StatusEvent> missed = new ArrayList<>();
    for (StatusEvent e : all.subList(from + 1, all.size()))
      if (subscriber.maySee(e))
        missed.add(e);
    return missed;
  }

  /**
   * Register one client connection. {@code firehose} is honored only for
   * admins; everyone else gets their own events regardless of what they ask.
   */
  public void register(SseEventSink sink, Sse sse, String userId, boolean admin, boolean firehose,
      String lastEventId) {
    register(new Subscriber(event -> sink.send(toSse(sse, event)), sink::isClosed, userId, admin,
        firehose && admin), lastEventId);
  }

  /** Attach an already-built subscriber (the SSE-free seam the scoping tests use). */
  void register(Subscriber subscriber, String lastEventId) {
    for (StatusEvent missed : replay(subscriber, lastEventId))
      subscriber.send(missed);
    this.subscribers.add(subscriber);
    log.debug("status stream: client attached (user={}, admin={})", subscriber.userId, subscriber.admin);
  }

  /** Connected client count — the SSE tests assert fan-out on it. */
  public int subscriberCount() {
    return this.subscribers.size();
  }
}
