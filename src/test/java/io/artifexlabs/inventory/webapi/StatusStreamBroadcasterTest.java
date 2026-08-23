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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.artifexlabs.inventory.api.events.StatusEvent;

/**
 * The SSE fan-out's scoping rules are a SECURITY boundary — one user must never receive another's operational detail —
 * so they are pinned here without a server, against the broadcaster's SSE-free seam.
 */
public class StatusStreamBroadcasterTest {

  private final static String ALICE = "01ALICE";
  private final static String BOB = "01BOB";

  /** A connected client that just remembers what it was sent. */
  private final static class Client {
    private final List<StatusEvent> received = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private StatusStreamBroadcaster.Subscriber attach(StatusStreamBroadcaster b, String userId, boolean admin,
        boolean firehose, String lastEventId) {
      StatusStreamBroadcaster.Subscriber s = new StatusStreamBroadcaster.Subscriber(this.received::add,
          this.closed::get, userId, admin, firehose && admin);
      b.register(s, lastEventId);
      return s;
    }

    private List<String> codes() {
      return this.received.stream().map(StatusEvent::code).toList();
    }
  }

  private static StatusEvent event(String code, String actor, String id) {
    return StatusEvent.error(code, "Something went wrong.").source("test").actor(actor).build().stamped(id,
        Instant.now());
  }

  @Test
  public void crossedActionsStayPrivateToTheirActor() {
    StatusStreamBroadcaster b = new StatusStreamBroadcaster();
    Client alice = new Client();
    Client bob = new Client();
    alice.attach(b, ALICE, false, false, null);
    bob.attach(b, BOB, false, false, null);

    b.accept(event("printer.tape-mismatch", ALICE, "01E1"));
    b.accept(event("bus.forbidden", BOB, "01E2"));

    assertEquals(List.of("printer.tape-mismatch"), alice.codes(), "Alice sees only her own");
    assertEquals(List.of("bus.forbidden"), bob.codes(), "Bob sees only his own");
  }

  @Test
  public void unattributedSystemTroubleGoesToAdminsNotUsers() {
    StatusStreamBroadcaster b = new StatusStreamBroadcaster();
    Client user = new Client();
    Client admin = new Client();
    user.attach(b, ALICE, false, false, null);
    admin.attach(b, BOB, true, false, null);

    // a printer nobody can reach belongs to whoever runs the system
    b.accept(event("printer.print-failed", null, "01E1"));

    assertTrue(user.codes().isEmpty(), "a plain user is not shown system faults");
    assertEquals(List.of("printer.print-failed"), admin.codes(), "the admin is");
  }

  @Test
  public void firehoseIsAdminOnly() {
    StatusStreamBroadcaster b = new StatusStreamBroadcaster();
    Client nosyUser = new Client();
    Client admin = new Client();
    // a non-admin asking for everything gets their own events regardless
    nosyUser.attach(b, ALICE, false, true, null);
    admin.attach(b, BOB, true, true, null);

    b.accept(event("printer.tape-mismatch", "01CAROL", "01E1"));

    assertTrue(nosyUser.codes().isEmpty(), "all=true must not escalate a non-admin");
    assertEquals(List.of("printer.tape-mismatch"), admin.codes(), "admin opt-in sees another user's event");
  }

  @Test
  public void reconnectWithLastEventIdReplaysOnlyTheGap() {
    StatusStreamBroadcaster b = new StatusStreamBroadcaster();
    b.accept(event("a.one", ALICE, "01E1"));
    b.accept(event("a.two", ALICE, "01E2"));
    b.accept(event("a.three", ALICE, "01E3"));

    Client reconnected = new Client();
    reconnected.attach(b, ALICE, false, false, "01E1");

    assertEquals(List.of("a.two", "a.three"), reconnected.codes(),
        "everything AFTER the last id the client saw, in order");
  }

  @Test
  public void replayNeverLeaksAcrossActors() {
    StatusStreamBroadcaster b = new StatusStreamBroadcaster();
    b.accept(event("a.one", ALICE, "01E1"));
    b.accept(event("b.secret", BOB, "01E2"));
    b.accept(event("a.two", ALICE, "01E3"));

    Client alice = new Client();
    alice.attach(b, ALICE, false, false, "01E1");

    assertEquals(List.of("a.two"), alice.codes(), "the gap is filtered by the same rules as live");
  }

  @Test
  public void anIdOlderThanTheRingReplaysNothingRatherThanLying() {
    StatusStreamBroadcaster b = new StatusStreamBroadcaster();
    b.accept(event("a.one", ALICE, "01E1"));

    Client alice = new Client();
    alice.attach(b, ALICE, false, false, "01LONG-EVICTED");

    assertTrue(alice.codes().isEmpty(), "an unknown cursor means the gap is unknowable — send nothing, resume live");
  }

  @Test
  public void closedClientsAreDroppedFromTheFanOut() {
    StatusStreamBroadcaster b = new StatusStreamBroadcaster();
    Client gone = new Client();
    gone.attach(b, ALICE, false, false, null);
    assertEquals(1, b.subscriberCount());

    gone.closed.set(true);
    b.accept(event("a.one", ALICE, "01E1"));

    assertTrue(gone.codes().isEmpty(), "nothing is sent to a closed sink");
    assertEquals(0, b.subscriberCount(), "and it stops being a subscriber");
  }

  @Test
  public void theRingStaysBounded() {
    StatusStreamBroadcaster b = new StatusStreamBroadcaster();
    for (int i = 0; i < StatusStreamBroadcaster.RING_SIZE + 50; i++)
      b.accept(event("a.event", ALICE, "01E" + i));

    Client alice = new Client();
    // the oldest surviving entry is the cursor; everything after it replays
    alice.attach(b, ALICE, false, false, "01E" + 50);
    assertEquals(StatusStreamBroadcaster.RING_SIZE - 1, alice.received.size(), "replay cannot exceed the ring");
  }
}
