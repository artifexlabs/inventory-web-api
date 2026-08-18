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
package org.lawfulevil.inventory.webapi;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.lawfulevil.inventory.api.AuditReader;
import org.lawfulevil.inventory.api.events.InventoryEvents;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

/**
 * Stage 3 gate (VERTICLES.md): with {@code inventory.events.bus=local}, a
 * mutation publishes its fact on the in-process bus — the same event (same
 * id) its audit row records, and only after that row is queryable
 * (publish-after-commit).
 */
@QuarkusTest
@TestProfile(LocalBusPublishTest.Profile.class)
class LocalBusPublishTest {
  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("inventory.events.bus", "local");
    }
  }

  @Inject
  Vertx vertx;

  @Inject
  AuditReader audit;

  @Test
  void mutationPublishesCommittedFactOnBothAddresses() throws Exception {
    BlockingQueue<JsonObject> firehose = new LinkedBlockingQueue<>();
    BlockingQueue<JsonObject> itemsOnly = new LinkedBlockingQueue<>();
    MessageConsumer<JsonObject> all = this.vertx.eventBus()
        .consumer(InventoryEvents.ADDRESS, m -> firehose.add(m.body()));
    MessageConsumer<JsonObject> items = this.vertx.eventBus()
        .consumer(InventoryEvents.categoryAddress("item.create"), m -> itemsOnly.add(m.body()));
    try {
      String created = given().header("Authorization", "Bearer dev-token").contentType(ContentType.JSON)
          .body(new JsonObject().put("name", "bus-item").put("type", "tool").encode())
          .post("/api/v1/items").then().statusCode(201).extract().asString();
      String itemId = new JsonObject(created).getString("id");

      JsonObject wire = poll(firehose, itemId);
      assertNotNull(wire, "item.create fact must reach the firehose address");
      assertEquals(InventoryEvents.VERSION, wire.getInteger("v"));
      assertEquals("item.create", wire.getString("action"));

      JsonObject categoryWire = poll(itemsOnly, itemId);
      assertNotNull(categoryWire, "item.create fact must reach inventory.events.item");
      assertEquals(wire.getString("id"), categoryWire.getString("id"));

      // publish-after-commit: the row behind the fact is already readable,
      // with the SAME event id the bus carried
      String eventId = wire.getString("id");
      assertTrue(this.audit.byTarget(itemId, 50).toCompletableFuture().get(5, TimeUnit.SECONDS).stream()
          .anyMatch(e -> e.getId().equals(eventId)), "bus fact must match a committed audit row");

      // round-trip through the wire helper preserves identity
      assertEquals(eventId, InventoryEvents.fromWire(wire).getId());
    } finally {
      all.unregister();
      items.unregister();
    }
  }

  private static JsonObject poll(BlockingQueue<JsonObject> queue, String targetId) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      JsonObject candidate = queue.poll(250, TimeUnit.MILLISECONDS);
      if (candidate != null && targetId.equals(candidate.getString("targetId")))
        return candidate;
    }
    return null;
  }
}
