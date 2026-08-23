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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Data manifests over the REST surface (memory backend): describing a medium, finding a file by content, and the
 * plain-text ingestion path that makes {@code find | sha256sum | curl} the whole client story.
 */
@QuarkusTest
public class DataManifestApiTest {

  private final static String TOKEN = "dev-token";

  /**
   * A digest unique to one test. The memory backend is shared across this class's methods, so tests that all stored the
   * same bytes would see each other's media in a by-hash answer — and that answer would look like a bug in
   * deduplication rather than in the fixture.
   */
  private static String hash(String seed) {
    String base = Integer.toHexString(seed.hashCode()).repeat(16);
    return base.substring(0, 64);
  }

  private static io.restassured.specification.RequestSpecification authed() {
    return given().header("Authorization", "Bearer " + TOKEN);
  }

  /** A medium that can hold files: an item carrying a PHYSICAL_MEDIA DataInfo. */
  private static String medium(String name) {
    String id = new JsonObject(
        authed().contentType(ContentType.JSON).body(new JsonObject().put("name", name).put("type", "disc").encode())
            .post("/api/v1/items").then().statusCode(201).extract().asString())
        .getString("id");
    JsonObject current = new JsonObject(
        authed().get("/api/v1/items/" + id).then().statusCode(200).extract().asString());
    current.put("dataInfo", new JsonObject().put("kind", "PHYSICAL_MEDIA").put("mutable", false).put("archive", false));
    authed().contentType(ContentType.JSON).body(current.encode()).put("/api/v1/items/" + id).then().statusCode(200);
    return id;
  }

  private static JsonObject entry(String path, String hash) {
    return new JsonObject().put("path", path).put("sizeBytes", 100).put("hashAlgorithm", "sha256").put("hash", hash);
  }

  private static JsonObject putManifest(String itemId, JsonArray entries) {
    return new JsonObject(authed().contentType(ContentType.JSON).body(entries.encode())
        .put("/api/v1/items/" + itemId + "/data/manifest").then().statusCode(200).extract().asString());
  }

  @Test
  public void describeAMediumThenFindAFileOnIt() {
    String disc = medium("backup-01");
    String hashA = hash("describe-a");
    String hashB = hash("describe-b");

    assertEquals(2, putManifest(disc, new JsonArray().add(entry("docs/a.txt", hashA)).add(entry("b.bin", hashB)))
        .getInteger("entries"));

    JsonArray listing = new JsonArray(
        authed().get("/api/v1/items/" + disc + "/data/entries").then().statusCode(200).extract().asString());
    assertEquals(2, listing.size());
    assertEquals("b.bin", listing.getJsonObject(0).getString("path"), "ordered by path");

    JsonObject summary = new JsonObject(
        authed().get("/api/v1/items/" + disc + "/data/summary").then().statusCode(200).extract().asString());
    assertEquals(2, summary.getInteger("entryCount"));
    assertEquals(200, summary.getInteger("totalBytes"));

    // the payoff: which medium holds this content?
    JsonArray found = new JsonArray(
        authed().get("/api/v1/data/by-hash/sha256/" + hashA).then().statusCode(200).extract().asString());
    assertEquals(1, found.size());
    assertEquals("backup-01", found.getJsonObject(0).getString("itemName"));
    assertEquals("docs/a.txt", found.getJsonObject(0).getString("path"));
  }

  @Test
  public void aManifestArrivesStraightFromSha256sum() {
    String disc = medium("backup-02");
    String HASH_A = hash("coreutils-a");
    String HASH_B = hash("coreutils-b");
    // exactly what `find . -type f -exec sha256sum {} +` prints, binary
    // marker and all — no client tool in between
    String coreutils = HASH_A + "  ./docs/notes.txt\n" + HASH_B + " *photos/cat.jpg\n\n";

    JsonObject stored = new JsonObject(authed().contentType(ContentType.TEXT).body(coreutils)
        .put("/api/v1/items/" + disc + "/data/manifest").then().statusCode(200).extract().asString());
    assertEquals(2, stored.getInteger("entries"));

    JsonArray listing = new JsonArray(
        authed().get("/api/v1/items/" + disc + "/data/entries").then().statusCode(200).extract().asString());
    assertEquals("docs/notes.txt", listing.getJsonObject(0).getString("path"), "the ./ prefix normalized away");
    assertEquals("photos/cat.jpg", listing.getJsonObject(1).getString("path"), "the * binary marker stripped");
  }

  @Test
  public void aMalformedManifestIsRefusedWithTheOffendingLine() {
    String disc = medium("backup-03");
    String HASH_A = hash("malformed-a");
    String broken = HASH_A + "  fine.txt\nthis is not a digest line\n";

    String error = authed().contentType(ContentType.TEXT).body(broken).put("/api/v1/items/" + disc + "/data/manifest")
        .then().statusCode(400).extract().asString();

    assertTrue(error.contains("line 2"), "the refusal names the line: " + error);
    // and nothing was stored: a partial manifest would understate the medium
    assertEquals(0,
        new JsonObject(
            authed().get("/api/v1/items/" + disc + "/data/summary").then().statusCode(200).extract().asString())
            .getInteger("entryCount"));
  }

  @Test
  public void aPlainCrateRefusesAManifest() {
    String HASH_A = hash("crate-a");
    String crate = new JsonObject(authed().contentType(ContentType.JSON)
        .body(new JsonObject().put("name", "wooden crate").put("type", "container").encode()).post("/api/v1/items")
        .then().statusCode(201).extract().asString()).getString("id");

    authed().contentType(ContentType.JSON).body(new JsonArray().add(entry("a.txt", HASH_A)).encode())
        .put("/api/v1/items/" + crate + "/data/manifest").then().statusCode(404);
  }

  @Test
  public void renamingAPathKeepsTheFileFindable() {
    String disc = medium("backup-04");
    String HASH_A = hash("rename-a");
    putManifest(disc, new JsonArray().add(entry("typo/a.txt", HASH_A)));

    JsonObject renamed = new JsonObject(
        authed().contentType(ContentType.JSON).body(new JsonObject().put("from", "typo").put("to", "docs").encode())
            .post("/api/v1/items/" + disc + "/data/rename").then().statusCode(200).extract().asString());
    assertEquals(1, renamed.getInteger("entries"));

    JsonArray found = new JsonArray(
        authed().get("/api/v1/data/by-hash/sha256/" + HASH_A).then().statusCode(200).extract().asString());
    assertEquals("docs/a.txt", found.getJsonObject(0).getString("path"));
  }

  @Test
  public void aBadDigestIsARequestErrorNotAServerError() {
    String HASH_A = hash("bad-digest-a");
    authed().get("/api/v1/data/by-hash/sha256/not-a-digest").then().statusCode(400);
    authed().get("/api/v1/data/by-hash/md5/" + HASH_A).then().statusCode(400);
  }
}
