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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.quarkus.test.Mock;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory fake of inventory-server mirroring its containment semantics, so
 * webapp tests run without a server process.
 */
@Mock
@ApplicationScoped
public class StubServerClient implements ServerClient {
  public final static String TOKEN = "stub-token";
  public final static String EMAIL = "admin@example.com";
  public final static String PASSWORD = "change-me";

  final Map<String, JsonObject> items = new LinkedHashMap<>();
  final Map<String, Set<String>> containment = new LinkedHashMap<>();
  boolean revoked = false;

  public StubServerClient() {
    reset();
  }

  public final void reset() {
    this.items.clear();
    this.containment.clear();
    this.revoked = false;
    addItem("box-1", "toolbox", "container");
    addItem("bin-1", "spare bin", "container");
    addItem("wrench-1", "wrench", "tool");
    this.containment.get("box-1").add("wrench-1");
  }

  private void addItem(String id, String name, String type) {
    this.items.put(id, new JsonObject().put("id", id).put("name", name).put("type", type)
        .put("timestamp", "2019-04-01T12:00:00Z"));
    this.containment.put(id, new LinkedHashSet<>());
  }

  private void check(String token) {
    if (this.revoked || !TOKEN.equals(token))
      throw new Unauthorized();
  }

  @Override
  public Optional<Login> login(String email, String password) {
    return EMAIL.equals(email) && PASSWORD.equals(password)
        ? Optional.of(new Login(TOKEN, new JsonObject().put("id", "u-1").put("email", EMAIL).put("admin", true)))
        : Optional.empty();
  }

  @Override
  public void logout(String token) {
    this.revoked = true;
  }

  private JsonObject withChildren(String id) {
    JsonObject j = this.items.get(id).copy();
    Set<String> kids = this.containment.get(id);
    if (!kids.isEmpty())
      j.put("containedItems", new JsonArray(kids.stream().map(k -> this.items.get(k).copy()).toList()));
    return j;
  }

  @Override
  public List<JsonObject> items(String token) {
    check(token);
    return this.items.keySet().stream().map(this::withChildren).toList();
  }

  @Override
  public Optional<JsonObject> item(String token, String id) {
    check(token);
    return this.items.containsKey(id) ? Optional.of(withChildren(id)) : Optional.empty();
  }

  @Override
  public List<JsonObject> containersOf(String token, String id) {
    check(token);
    return this.containment.entrySet().stream().filter(e -> e.getValue().contains(id))
        .map(e -> withChildren(e.getKey())).toList();
  }

  @Override
  public boolean addToContainer(String token, String containerId, String itemId) {
    check(token);
    if (!this.items.containsKey(containerId) || !this.items.containsKey(itemId) || containerId.equals(itemId))
      return false;
    return this.containment.get(containerId).add(itemId);
  }

  @Override
  public boolean removeFromContainer(String token, String containerId, String itemId) {
    check(token);
    Set<String> kids = this.containment.get(containerId);
    return kids != null && kids.remove(itemId);
  }

  @Override
  public boolean moveToContainer(String token, String itemId, String containerId) {
    check(token);
    if (!this.items.containsKey(containerId) || !this.items.containsKey(itemId) || containerId.equals(itemId))
      return false;
    this.containment.values().forEach(kids -> kids.remove(itemId));
    this.containment.get(containerId).add(itemId);
    return true;
  }
}
