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
  final Map<String, JsonObject> users = new LinkedHashMap<>();
  final List<JsonObject> auditLog = new java.util.ArrayList<>();
  final List<JsonObject> issuedTokens = new java.util.ArrayList<>();
  boolean revoked = false;
  private int nextId = 0;

  public StubServerClient() {
    reset();
  }

  public final void reset() {
    this.items.clear();
    this.containment.clear();
    this.users.clear();
    this.auditLog.clear();
    this.issuedTokens.clear();
    this.revoked = false;
    this.nextId = 0;
    this.locationsById.clear();
    this.assetInfos.clear();
    this.assetBytes.clear();
    this.printedLabels.clear();
    addItem("box-1", "toolbox", "container");
    addItem("bin-1", "spare bin", "container");
    addItem("wrench-1", "wrench", "tool");
    this.containment.get("box-1").add("wrench-1");
    this.users.put("u-1", new JsonObject().put("id", "u-1").put("email", EMAIL).put("admin", true));
  }

  private void auditEntry(String action, String targetId) {
    this.auditLog.add(new JsonObject().put("timestamp", "2019-04-01T12:00:00Z").put("principal", EMAIL)
        .put("action", action).put("targetId", targetId));
  }

  final Map<String, JsonObject> locationsById = new LinkedHashMap<>();
  final Map<String, JsonObject> assetInfos = new LinkedHashMap<>();
  final Map<String, byte[]> assetBytes = new LinkedHashMap<>();

  @Override
  public List<JsonObject> locations(String token) {
    check(token);
    return this.locationsById.values().stream().map(JsonObject::copy).toList();
  }

  @Override
  public Optional<JsonObject> createLocation(String token, String name, Double latitude, Double longitude) {
    check(token);
    String id = "loc-" + (++this.nextId);
    JsonObject l = new JsonObject().put("id", id).put("name", name);
    if (latitude != null && longitude != null)
      l.put("latitude", latitude).put("longitude", longitude);
    this.locationsById.put(id, l);
    return Optional.of(l.copy());
  }

  @Override
  public boolean deleteLocation(String token, String id) {
    check(token);
    boolean referenced = this.items.values().stream().anyMatch(i -> id.equals(i.getString("locationId")));
    return !referenced && this.locationsById.remove(id) != null;
  }

  @Override
  public List<JsonObject> assetsFor(String token, String itemId) {
    check(token);
    return this.assetInfos.values().stream().filter(a -> a.getString("itemId").equals(itemId))
        .map(JsonObject::copy).toList();
  }

  @Override
  public Optional<JsonObject> uploadAsset(String token, String itemId, String filename, String contentType,
      byte[] data) {
    check(token);
    if (!this.items.containsKey(itemId))
      return Optional.empty();
    String id = "asset-" + (++this.nextId);
    JsonObject info = new JsonObject().put("id", id).put("itemId", itemId).put("filename", filename)
        .put("contentType", contentType).put("sizeBytes", data.length)
        .put("timestamp", "2019-04-01T12:00:00Z");
    this.assetInfos.put(id, info);
    this.assetBytes.put(id, data.clone());
    auditEntry("asset.attach", itemId);
    return Optional.of(info.copy());
  }

  @Override
  public Optional<AssetData> downloadAsset(String token, String assetId) {
    check(token);
    JsonObject info = this.assetInfos.get(assetId);
    return info == null ? Optional.empty()
        : Optional.of(new AssetData(this.assetBytes.get(assetId), info.getString("contentType"),
            info.getString("filename")));
  }

  @Override
  public boolean deleteAsset(String token, String assetId) {
    check(token);
    this.assetBytes.remove(assetId);
    return this.assetInfos.remove(assetId) != null;
  }

  final List<String> printedLabels = new java.util.ArrayList<>();

  @Override
  public Optional<byte[]> qrPng(String token, String itemId) {
    check(token);
    return this.items.containsKey(itemId)
        ? Optional.of(new byte[] { (byte) 0x89, 'P', 'N', 'G', 'q', 'r' })
        : Optional.empty();
  }

  @Override
  public boolean printLabel(String token, String itemId) {
    check(token);
    if (!this.items.containsKey(itemId))
      return false;
    this.printedLabels.add(itemId);
    return true;
  }

  public List<String> printedLabels() {
    return List.copyOf(this.printedLabels);
  }

  /** Test seeding hooks — must be methods so calls pass through the CDI client proxy. */
  public void seedToken(String token, String userId) {
    this.issuedTokens.add(new JsonObject().put("token", token).put("userId", userId)
        .put("issuedAt", "2019-04-01T12:00:00Z").put("revoked", false));
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
  public JsonObject me(String token) {
    check(token);
    return this.users.get("u-1").copy();
  }

  @Override
  public Optional<Login> exchange(String email, String displayName) {
    // mirrors invited-policy: only known users exchange successfully
    return this.users.values().stream().filter(u -> u.getString("email").equalsIgnoreCase(email)).findFirst()
        .map(u -> new Login(TOKEN, u.copy()));
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

  @Override
  public Optional<JsonObject> createItem(String token, String name, String displayName, String type) {
    check(token);
    String id = "item-" + (++this.nextId);
    addItem(id, name, type == null ? "_" : type);
    if (displayName != null)
      this.items.get(id).put("displayName", displayName);
    auditEntry("item.create", id);
    return Optional.of(this.items.get(id).copy());
  }

  @Override
  public boolean updateItem(String token, JsonObject item) {
    check(token);
    String id = item.getString("id");
    if (!this.items.containsKey(id))
      return false;
    this.items.put(id, item.copy());
    auditEntry("item.update", id);
    return true;
  }

  @Override
  public boolean deleteItem(String token, String id) {
    check(token);
    if (this.items.remove(id) == null)
      return false;
    this.containment.remove(id);
    this.containment.values().forEach(kids -> kids.remove(id));
    auditEntry("item.delete", id);
    return true;
  }

  @Override
  public List<JsonObject> users(String token) {
    check(token);
    return this.users.values().stream().map(JsonObject::copy).toList();
  }

  @Override
  public Optional<JsonObject> createUser(String token, String email, String displayName, String password,
      boolean admin) {
    check(token);
    String id = "user-" + (++this.nextId);
    JsonObject u = new JsonObject().put("id", id).put("email", email).put("admin", admin);
    if (displayName != null)
      u.put("displayName", displayName);
    this.users.put(id, u);
    auditEntry("user.create", id);
    return Optional.of(u.copy());
  }

  @Override
  public boolean deleteUser(String token, String id) {
    check(token);
    boolean did = this.users.remove(id) != null;
    if (did)
      auditEntry("user.delete", id);
    return did;
  }

  @Override
  public boolean setAdmin(String token, String id, boolean admin) {
    check(token);
    JsonObject u = this.users.get(id);
    if (u == null)
      return false;
    u.put("admin", admin);
    auditEntry("user.set-admin", id);
    return true;
  }

  @Override
  public List<JsonObject> tokensFor(String token, String userId) {
    check(token);
    return this.issuedTokens.stream().filter(t -> t.getString("userId").equals(userId)).map(JsonObject::copy)
        .toList();
  }

  @Override
  public boolean revokeToken(String token, String tokenToRevoke) {
    check(token);
    for (JsonObject t : this.issuedTokens)
      if (t.getString("token").equals(tokenToRevoke) && !Boolean.TRUE.equals(t.getBoolean("revoked"))) {
        t.put("revoked", true);
        auditEntry("token.revoke", tokenToRevoke);
        return true;
      }
    return false;
  }

  @Override
  public List<JsonObject> auditRecent(String token, int limit, int offset) {
    check(token);
    List<JsonObject> reversed = new java.util.ArrayList<>(this.auditLog);
    java.util.Collections.reverse(reversed);
    return reversed.stream().skip(offset).limit(limit).map(JsonObject::copy).toList();
  }

  @Override
  public List<JsonObject> auditFor(String token, String targetId, int limit) {
    check(token);
    List<JsonObject> reversed = new java.util.ArrayList<>(this.auditLog);
    java.util.Collections.reverse(reversed);
    return reversed.stream().filter(e -> e.getString("targetId").equals(targetId)).limit(limit)
        .map(JsonObject::copy).toList();
  }
}
