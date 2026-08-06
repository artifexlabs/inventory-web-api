/*
 * @formatter:off
 * Copyright © 2019 admin (admin@infrastructurebuilder.org)
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
package org.lawfulevil.inventory.webapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonObject;

public class UserLookupTest {

  /** Counts me() calls; every other ServerClient method is unused here. */
  private static final class CountingClient implements ServerClient {
    final AtomicInteger meCalls = new AtomicInteger();
    volatile boolean unauthorized = false;

    @Override
    public JsonObject me(String token) {
      this.meCalls.incrementAndGet();
      if (this.unauthorized)
        throw new Unauthorized();
      return new JsonObject().put("id", "u-1").put("email", "a@example.com").put("admin", true);
    }

    @Override
    public Optional<Login> login(String email, String password) {
      return Optional.empty();
    }

    @Override
    public Optional<Login> exchange(String email, String displayName) {
      return Optional.empty();
    }

    @Override
    public void logout(String token) {
    }

    @Override
    public List<JsonObject> items(String token) {
      return List.of();
    }

    @Override
    public Optional<JsonObject> item(String token, String id) {
      return Optional.empty();
    }

    @Override
    public List<JsonObject> containersOf(String token, String id) {
      return List.of();
    }

    @Override
    public boolean addToContainer(String token, String containerId, String itemId) {
      return false;
    }

    @Override
    public boolean removeFromContainer(String token, String containerId, String itemId) {
      return false;
    }

    @Override
    public boolean moveToContainer(String token, String itemId, String containerId) {
      return false;
    }

    @Override
    public Optional<JsonObject> createItem(String token, String name, String displayName, String type) {
      return Optional.empty();
    }

    @Override
    public boolean updateItem(String token, JsonObject item) {
      return false;
    }

    @Override
    public boolean deleteItem(String token, String id) {
      return false;
    }

    @Override
    public List<JsonObject> users(String token) {
      return List.of();
    }

    @Override
    public Optional<JsonObject> createUser(String token, String email, String displayName, String password,
        boolean admin) {
      return Optional.empty();
    }

    @Override
    public boolean deleteUser(String token, String id) {
      return false;
    }

    @Override
    public boolean setAdmin(String token, String id, boolean admin) {
      return false;
    }

    @Override
    public List<JsonObject> tokensFor(String token, String userId) {
      return List.of();
    }

    @Override
    public boolean revokeToken(String token, String tokenToRevoke) {
      return false;
    }

    @Override
    public List<JsonObject> auditRecent(String token, int limit, int offset) {
      return List.of();
    }

    @Override
    public List<JsonObject> auditFor(String token, String targetId, int limit) {
      return List.of();
    }

    @Override
    public List<JsonObject> locations(String token) {
      return List.of();
    }

    @Override
    public Optional<JsonObject> createLocation(String token, String name, Double latitude, Double longitude) {
      return Optional.empty();
    }

    @Override
    public boolean deleteLocation(String token, String id) {
      return false;
    }

    @Override
    public List<JsonObject> assetsFor(String token, String itemId) {
      return List.of();
    }

    @Override
    public Optional<JsonObject> uploadAsset(String token, String itemId, String filename, String contentType,
        byte[] data) {
      return Optional.empty();
    }

    @Override
    public Optional<AssetData> downloadAsset(String token, String assetId) {
      return Optional.empty();
    }

    @Override
    public boolean deleteAsset(String token, String assetId) {
      return false;
    }

    @Override
    public Optional<byte[]> qrPng(String token, String itemId) {
      return Optional.empty();
    }

    @Override
    public boolean printLabel(String token, String itemId) {
      return false;
    }
  }

  @Test
  public void testZeroTtlAlwaysHitsServer() {
    CountingClient client = new CountingClient();
    UserLookup lookup = new UserLookup(client, Duration.ZERO);
    lookup.me("t");
    lookup.me("t");
    assertEquals(2, client.meCalls.get());
  }

  @Test
  public void testTtlCachesWithinWindow() {
    CountingClient client = new CountingClient();
    UserLookup lookup = new UserLookup(client, Duration.ofHours(1));
    assertEquals(true, lookup.me("t").getBoolean("admin"));
    lookup.me("t");
    lookup.me("t");
    assertEquals(1, client.meCalls.get());
    // distinct tokens are cached independently
    lookup.me("other");
    assertEquals(2, client.meCalls.get());
  }

  @Test
  public void testInvalidateForcesRefetch() {
    CountingClient client = new CountingClient();
    UserLookup lookup = new UserLookup(client, Duration.ofHours(1));
    lookup.me("t");
    lookup.invalidate("t");
    lookup.me("t");
    assertEquals(2, client.meCalls.get());
  }

  @Test
  public void testUnauthorizedEvictsAndPropagates() {
    CountingClient client = new CountingClient();
    UserLookup lookup = new UserLookup(client, Duration.ofHours(1));
    lookup.me("t");
    lookup.invalidate("t");
    client.unauthorized = true;
    assertThrows(ServerClient.Unauthorized.class, () -> lookup.me("t"));
    client.unauthorized = false;
    lookup.me("t");
    assertEquals(3, client.meCalls.get());
  }
}
