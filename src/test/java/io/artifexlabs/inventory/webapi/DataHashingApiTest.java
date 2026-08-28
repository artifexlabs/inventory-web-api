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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The two answers stage 1 and stage 2 produced, reachable by {@code curl} at last.
 *
 * <p>
 * Both were proven on both backends by the parity kit long before this: what these tests pin is the WIRING — that the
 * bus vocabulary, the role map, the verticle's forwarding table and the JAX-RS routes all agree, which is the part no
 * unit test can see.
 */
@QuarkusTest
public class DataHashingApiTest {

  private final static String TOKEN = "dev-token";

  private static io.restassured.specification.RequestSpecification authed() {
    return given().header("Authorization", "Bearer " + TOKEN);
  }

  /** A digest unique to one seed; the memory backend is shared across this class's methods. */
  private static String hash(String seed) {
    return Integer.toHexString(seed.hashCode()).repeat(16).substring(0, 64);
  }

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

  private static void putManifest(String itemId, JsonArray entries) {
    authed().contentType(ContentType.JSON).body(entries.encode()).put("/api/v1/items/" + itemId + "/data/manifest")
        .then().statusCode(200);
  }

  /** An entry a find-shaped scan produces: size and path, nothing hashed. */
  private static JsonObject unhashed(String path, long size) {
    return new JsonObject().put("path", path).put("sizeBytes", size);
  }

  private static JsonObject hashed(String path, long size, String seed) {
    return unhashed(path, size).put("hashAlgorithm", "sha256").put("hash", hash(seed));
  }

  /** A folder of {@code n} files under {@code prefix}, sized so a structure hash is distinctive. */
  private static JsonArray folder(String prefix, int n, long base) {
    JsonArray out = new JsonArray();
    for (int i = 0; i < n; i++)
      out.add(unhashed(prefix + "/f" + i + ".bin", base + i));
    return out;
  }

  private static JsonObject progressOf(String itemId) {
    return new JsonObject(
        authed().get("/api/v1/items/" + itemId + "/data/progress").then().statusCode(200).extract().asString());
  }

  private static JsonArray sections(String path) {
    return new JsonArray(authed().get(path).then().statusCode(200).extract().asString());
  }

  private static List<String> mediaIn(JsonObject group) {
    return group.getJsonArray("locations").stream().map(JsonObject.class::cast).map(l -> l.getString("itemId"))
        .toList();
  }

  @Test
  public void progressReportsAFreshManifestAsEntirelyPending() {
    String disc = medium("hashing-fresh");
    putManifest(disc, folder("data", 3, 1000));

    JsonObject p = progressOf(disc);
    assertEquals(3, p.getInteger("pending"), "find describes a medium and hashes nothing");
    assertEquals(0, p.getInteger("done"));
    assertFalse(p.getBoolean("complete"));
    assertEquals(0.0, p.getDouble("fraction"), 0.001);
    assertTrue(p.getLong("pendingBytes") > 0, "the bytes still to read are what says how long this will take");
  }

  @Test
  public void aFullyHashedMediumIsCompleteAndIntact() {
    String disc = medium("hashing-done");
    putManifest(disc, new JsonArray().add(hashed("a.txt", 100, "done-a")).add(hashed("b.txt", 100, "done-b")));

    JsonObject p = progressOf(disc);
    assertEquals(2, p.getInteger("done"));
    assertEquals(0, p.getInteger("pending"));
    assertTrue(p.getBoolean("complete"));
    assertTrue(p.getBoolean("intact"),
        "complete AND undamaged; a medium that finished with unreadable files is the first but not the second");
    assertEquals(1.0, p.getDouble("fraction"), 0.001);
  }

  @Test
  public void anItemHoldingNoManifestReportsNothingToDo() {
    String disc = medium("hashing-empty");

    JsonObject p = progressOf(disc);
    assertTrue(p.getBoolean("complete"), "empty rather than 404, the same shape summaryOf uses");
    assertEquals(0, p.getInteger("pending"));
  }

  @Test
  public void aFolderCopiedToAnotherDiscIsFoundAtADifferentPath() {
    // the same eight files, in a different place on each medium: the question
    // findMirrorsOf could never answer, because it compares paths
    String one = medium("sections-one");
    String two = medium("sections-two");
    putManifest(one, folder("originals/holiday", 8, 5000));
    putManifest(two, folder("archive/2019/holiday", 8, 5000));

    JsonArray found = sections("/api/v1/data/sections?match=structure");
    JsonObject group = found.stream().map(JsonObject.class::cast)
        .filter(g -> mediaIn(g).contains(one) && mediaIn(g).contains(two)).findFirst()
        .orElseThrow(() -> new AssertionError("the relocated copy was not reported: " + found.encodePrettily()));
    assertEquals(8, group.getLong("subtreeFiles"));
    assertEquals(2, group.getInteger("places"), "grouped by identity: the answer is how many places, not a flat list");
  }

  @Test
  public void theDefaultFloorSuppressesSmallCoincidencesAndCanBeLowered() {
    String one = medium("floor-one");
    String two = medium("floor-two");
    // two files apiece — the shape 46.7% of real directories have, and the
    // reason a floor exists at all
    putManifest(one, folder("src/main", 2, 77));
    putManifest(two, folder("elsewhere/src/main", 2, 77));

    JsonArray defaulted = sections("/api/v1/items/" + one + "/data/sections");
    assertTrue(defaulted.stream().map(JsonObject.class::cast).noneMatch(g -> mediaIn(g).contains(two)),
        "at the default floor of 8 this pair is noise, and reporting it would bury the real answer");

    JsonArray lowered = sections("/api/v1/items/" + one + "/data/sections?minFiles=2");
    assertTrue(lowered.stream().map(JsonObject.class::cast).anyMatch(g -> mediaIn(g).contains(two)),
        "and the floor is the caller's to lower when they know what they are asking for");
  }

  @Test
  public void aMediumScopedQueryOnlyAnswersAboutThatMedium() {
    String mine = medium("scoped-mine");
    String other = medium("scoped-other");
    String unrelated = medium("scoped-unrelated");
    putManifest(mine, folder("photos", 9, 300));
    putManifest(other, folder("backup/photos", 9, 300));
    // a duplicate pair that has nothing to do with `mine`
    putManifest(unrelated, folder("music", 9, 900));

    JsonArray found = sections("/api/v1/items/" + mine + "/data/sections?match=structure");
    assertTrue(found.stream().map(JsonObject.class::cast).allMatch(g -> mediaIn(g).contains(mine)),
        "every group returned has to involve the medium that was asked about: " + found.encodePrettily());
    assertTrue(found.stream().map(JsonObject.class::cast).anyMatch(g -> mediaIn(g).contains(other)));
  }

  private static JsonObject rollUp(String itemId) {
    return new JsonObject(
        authed().post("/api/v1/items/" + itemId + "/data/rollup").then().statusCode(200).extract().asString());
  }

  @Test
  public void overlapTellsACopyFromASupersetInsteadOfListingEveryFile() {
    String disc = medium("overlap-disc");
    String twin = medium("overlap-twin");
    String bigger = medium("overlap-bigger");
    JsonArray tree = new JsonArray().add(hashed("data/a.bin", 100, "ov-a")).add(hashed("data/b.bin", 200, "ov-b"));
    putManifest(disc, tree);
    putManifest(twin, tree);
    putManifest(bigger, tree.copy().add(hashed("data/c.bin", 300, "ov-c")));
    for (String m : List.of(disc, twin, bigger))
      assertTrue(rollUp(m).getInteger("directories") > 0, "the sweep is what makes `identical` answerable");

    JsonArray found = new JsonArray(
        authed().get("/api/v1/items/" + disc + "/data/overlap").then().statusCode(200).extract().asString());
    JsonObject sameTree = found.stream().map(JsonObject.class::cast).filter(o -> o.getString("itemId").equals(twin))
        .findFirst().orElseThrow();
    JsonObject superset = found.stream().map(JsonObject.class::cast).filter(o -> o.getString("itemId").equals(bigger))
        .findFirst().orElseThrow();

    assertEquals(2, sameTree.getLong("sharedEntries"));
    assertTrue(sameTree.getBoolean("identical"), "the same tree all the way down");
    assertFalse(superset.getBoolean("identical"), "a superset is not a copy, and the old return type could not say so");
    assertTrue(superset.getBoolean("contains"), "but everything this disc holds is on it");
    assertEquals(3, superset.getLong("theirEntries"));
  }

  @Test
  public void anUndamagedMediumHasAnEmptyRepairIndex() {
    String disc = medium("repairs-clean");
    putManifest(disc, new JsonArray().add(hashed("a.txt", 10, "clean-a")));

    // damage is recorded by the worker in-process; there is deliberately no HTTP
    // route that marks a file unreadable, because nothing but a reader can know
    assertTrue(new JsonArray(
        authed().get("/api/v1/items/" + disc + "/data/repairs").then().statusCode(200).extract().asString()).isEmpty());
  }

  @Test
  public void merkleMatchingIsAvailableOnlyAfterTheSweep() {
    String one = medium("sweep-one");
    String two = medium("sweep-two");
    JsonArray folder = new JsonArray();
    for (int i = 0; i < 8; i++)
      folder.add(hashed("shared/f" + i + ".bin", 1000 + i, "sweep-" + i));
    putManifest(one, folder);
    putManifest(two, folder);

    JsonArray before = sections("/api/v1/items/" + one + "/data/sections?match=merkle");
    assertTrue(before.isEmpty(), "the files carry digests, but nothing has folded them into a tree yet");

    rollUp(one);
    rollUp(two);

    JsonArray after = sections("/api/v1/items/" + one + "/data/sections?match=merkle");
    assertTrue(after.stream().map(JsonObject.class::cast).anyMatch(g -> mediaIn(g).contains(two)),
        "and now the fold exists: " + after.encodePrettily());
  }

  @Test
  public void anUnknownMatchOrScopeIsRefusedRatherThanQuietlyDefaulted() {
    String disc = medium("sections-bad-args");
    putManifest(disc, folder("data", 8, 10));

    authed().get("/api/v1/items/" + disc + "/data/sections?match=nonsense").then().statusCode(400);
    authed().get("/api/v1/data/sections?scope=sideways").then().statusCode(400);
  }
}
