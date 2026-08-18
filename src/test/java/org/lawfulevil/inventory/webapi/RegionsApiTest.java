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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Phase 8 spatial annotation over the REST surface (memory backend): photo →
 * bare boxes (draw-then-describe) → items, with containment, region links,
 * audit rows, and capture-coordinate extraction (EXIF vs explicit).
 */
@QuarkusTest
public class RegionsApiTest {
  private final static String TOKEN = "dev-token";

  private static io.restassured.specification.RequestSpecification authed() {
    return given().header("Authorization", "Bearer " + TOKEN);
  }

  private static String createItem(String name, String type) {
    return new JsonObject(authed().contentType(ContentType.JSON)
        .body(new JsonObject().put("name", name).put("type", type).encode()).post("/api/v1/items").then()
        .statusCode(201).extract().asString()).getString("id");
  }

  private static String uploadImage(String itemId, byte[] bytes, String query) {
    return new JsonObject(authed().header("X-Filename", "space.jpg").contentType("image/jpeg").body(bytes)
        .post("/api/v1/items/" + itemId + "/assets" + query).then().statusCode(201).extract().asString())
        .getString("id");
  }

  @Test
  public void testDrawThenDescribeFlow() {
    String spaceId = createItem("garage-shelf", "container");
    String assetId = uploadImage(spaceId, jpegNoGps(), "");

    // draw two bare boxes, no data yet
    String box1 = new JsonObject(authed().contentType(ContentType.JSON)
        .body(new JsonObject().put("x", 0.1).put("y", 0.2).put("w", 0.3).put("h", 0.4).encode())
        .post("/api/v1/assets/" + assetId + "/regions").then().statusCode(201)
        .body("itemId", nullValue()).extract().asString()).getString("id");
    authed().contentType(ContentType.JSON)
        .body(new JsonObject().put("x", 0.6).put("y", 0.1).put("w", 0.2).put("h", 0.2).put("label", "later")
            .encode())
        .post("/api/v1/assets/" + assetId + "/regions").then().statusCode(201);
    authed().get("/api/v1/assets/" + assetId + "/regions").then().statusCode(200).body("size()", equalTo(2));

    // describe box1: becomes an item contained in the space
    String hammerId = new JsonObject(authed().contentType(ContentType.JSON)
        .body(new JsonObject().put("name", "hammer").put("type", "tool").put("containerId", spaceId).encode())
        .post("/api/v1/regions/" + box1 + "/make-item").then().statusCode(201)
        .body("name", equalTo("hammer")).extract().asString()).getString("id");

    authed().get("/api/v1/items/" + hammerId + "/container").then().statusCode(200)
        .body("id", equalTo(spaceId));
    JsonArray regions = new JsonArray(
        authed().get("/api/v1/assets/" + assetId + "/regions").then().statusCode(200).extract().asString());
    Assertions.assertEquals(hammerId, regions.stream().map(o -> (JsonObject) o)
        .filter(r -> r.getString("id").equals(box1)).findFirst().orElseThrow().getString("itemId"));
    authed().get("/api/v1/audit/target/" + hammerId).then().statusCode(200)
        .body("action", hasItem("item.create-from-region")).body("action", hasItem("item.create"));

    // an already-linked region cannot be described twice
    authed().contentType(ContentType.JSON).body(new JsonObject().put("name", "x").put("type", "t").encode())
        .post("/api/v1/regions/" + box1 + "/make-item").then().statusCode(404);
  }

  @Test
  public void testDotDenotesAnItemWithoutABox() {
    // ongoing item 8: a zero-size region is a point marker — same
    // draw-then-describe lifecycle as a box, no drawing required
    String spaceId = createItem("pegboard", "container");
    String assetId = uploadImage(spaceId, jpegNoGps(), "");

    String dot = new JsonObject(authed().contentType(ContentType.JSON)
        .body(new JsonObject().put("x", 0.5).put("y", 0.25).put("w", 0).put("h", 0).encode())
        .post("/api/v1/assets/" + assetId + "/regions").then().statusCode(201)
        .body("itemId", nullValue()).extract().asString()).getString("id");
    authed().get("/api/v1/assets/" + assetId + "/regions").then().statusCode(200)
        .body("size()", equalTo(1)).body("[0].w", equalTo(0.0f)).body("[0].h", equalTo(0.0f));

    String plierId = new JsonObject(authed().contentType(ContentType.JSON)
        .body(new JsonObject().put("name", "pliers").put("type", "tool").put("containerId", spaceId).encode())
        .post("/api/v1/regions/" + dot + "/make-item").then().statusCode(201)
        .body("name", equalTo("pliers")).extract().asString()).getString("id");
    authed().get("/api/v1/items/" + plierId + "/container").then().statusCode(200)
        .body("id", equalTo(spaceId));
  }

  @Test
  public void testCreateItemFromPhoto() {
    // ongoing item 2: one call — item created, photo attached, EXIF pins it
    JsonObject made = new JsonObject(authed().header("X-Filename", "garage.jpg").contentType("image/jpeg")
        .body(jpegWithGps()).post("/api/v1/items/from-photo?name=photo-garage&type=location").then()
        .statusCode(201).extract().asString());
    JsonObject item = made.getJsonObject("item");
    JsonObject asset = made.getJsonObject("asset");
    Assertions.assertEquals("location", item.getString("type"));
    Assertions.assertNotNull(item.getDouble("latitude"), "EXIF pinned the created place itself");
    Assertions.assertEquals(item.getString("id"), asset.getString("itemId"));
    Assertions.assertEquals("photo", asset.getString("kind"));
    authed().get("/api/v1/items/" + item.getString("id") + "/assets").then().statusCode(200)
        .body("size()", equalTo(1));
    authed().get("/api/v1/audit/target/" + item.getString("id")).then().statusCode(200)
        .body("action", hasItem("item.create")).body("action", hasItem("asset.attach"));

    // contained + map-kind variant (ongoing item 3 rides the same call)
    JsonObject wall = new JsonObject(authed().header("X-Filename", "plan.png").contentType("image/png")
        .body(jpegNoGps())
        .post("/api/v1/items/from-photo?name=wall-map&type=location&kind=map&container=" + item.getString("id"))
        .then().statusCode(201).extract().asString());
    Assertions.assertEquals("map", wall.getJsonObject("asset").getString("kind"));
    authed().get("/api/v1/items/" + wall.getJsonObject("item").getString("id") + "/container").then()
        .statusCode(200).body("id", equalTo(item.getString("id")));

    // refusals: missing name, unknown container
    authed().contentType("image/png").body(jpegNoGps()).post("/api/v1/items/from-photo").then().statusCode(400);
    authed().contentType("image/png").body(jpegNoGps())
        .post("/api/v1/items/from-photo?name=x&container=missing").then().statusCode(404);
  }

  @Test
  public void testMapKindRoundTripsOnPlainUpload() {
    String wallId = createItem("wall", "location");
    String assetId = uploadImage(wallId, jpegNoGps(), "?kind=map");
    authed().get("/api/v1/items/" + wallId + "/assets").then().statusCode(200)
        .body("[0].kind", equalTo("map")).body("[0].id", equalTo(assetId));
    // boxes on a map become places through the SAME make-item call
    String placeId = new JsonObject(authed().contentType(ContentType.JSON)
        .body(new JsonObject().put("x", 0.1).put("y", 0.1).put("w", 0.3).put("h", 0.3)
            .put("name", "corner-shelf").put("type", "location").put("containerId", wallId).encode())
        .post("/api/v1/assets/" + assetId + "/regions/make-item").then().statusCode(201)
        .body("type", equalTo("location")).extract().asString()).getString("id");
    authed().get("/api/v1/items/" + placeId + "/container").then().statusCode(200).body("id", equalTo(wallId));
  }

  @Test
  public void testOneShotCreateItemFromRegion() {
    String spaceId = createItem("workbench", "container");
    String assetId = uploadImage(spaceId, jpegNoGps(), "");
    String wrenchId = new JsonObject(authed().contentType(ContentType.JSON)
        .body(new JsonObject().put("x", 0.0).put("y", 0.0).put("w", 0.5).put("h", 0.5).put("name", "wrench")
            .put("type", "tool").put("containerId", spaceId).encode())
        .post("/api/v1/assets/" + assetId + "/regions/make-item").then().statusCode(201).extract().asString())
        .getString("id");
    authed().get("/api/v1/assets/" + assetId + "/regions").then().statusCode(200).body("size()", equalTo(1))
        .body("[0].itemId", equalTo(wrenchId));
    authed().get("/api/v1/items/" + wrenchId + "/container").then().statusCode(200).body("id", equalTo(spaceId));
  }

  @Test
  public void testDeleteRegionAndUnknownAsset404s() {
    String spaceId = createItem("closet", "container");
    String assetId = uploadImage(spaceId, jpegNoGps(), "");
    String boxId = new JsonObject(authed().contentType(ContentType.JSON)
        .body(new JsonObject().put("x", 0.1).put("y", 0.1).put("w", 0.1).put("h", 0.1).encode())
        .post("/api/v1/assets/" + assetId + "/regions").then().statusCode(201).extract().asString())
        .getString("id");
    authed().delete("/api/v1/regions/" + boxId).then().statusCode(204);
    authed().delete("/api/v1/regions/" + boxId).then().statusCode(404);
    authed().get("/api/v1/assets/" + assetId + "/regions").then().statusCode(200).body("size()", equalTo(0));
    authed().get("/api/v1/audit/target/" + spaceId).then().statusCode(200)
        .body("action", hasItem("region.create")).body("action", hasItem("region.delete"));
    authed().contentType(ContentType.JSON)
        .body(new JsonObject().put("x", 0.1).put("y", 0.1).put("w", 0.1).put("h", 0.1).encode())
        .post("/api/v1/assets/nope/regions").then().statusCode(404);
  }

  @Test
  public void testCaptureCoordinatesExifAndExplicit() {
    String itemId = createItem("photo-holder", "thing");
    // EXIF only: extracted server-side
    authed().header("X-Filename", "exif.jpg").contentType("image/jpeg").body(jpegWithGps())
        .post("/api/v1/items/" + itemId + "/assets").then().statusCode(201)
        .body("latitude", equalTo(35.5f)).body("longitude", equalTo(-97.25f));
    // explicit beats EXIF (the mobile path: phone GPS at capture)
    authed().header("X-Filename", "explicit.jpg").contentType("image/jpeg").body(jpegWithGps())
        .post("/api/v1/items/" + itemId + "/assets?lat=1.5&long=2.5").then().statusCode(201)
        .body("latitude", equalTo(1.5f)).body("longitude", equalTo(2.5f));
    // no data at all: fields absent
    authed().header("X-Filename", "plain.jpg").contentType("image/jpeg").body(jpegNoGps())
        .post("/api/v1/items/" + itemId + "/assets").then().statusCode(201)
        .body("latitude", nullValue()).body("id", notNullValue());
    // and the list view carries them
    authed().get("/api/v1/items/" + itemId + "/assets").then().statusCode(200)
        .body("[0].latitude", equalTo(35.5f)).body("[1].latitude", equalTo(1.5f));
  }

  private static byte[] jpegNoGps() {
    return new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9 };
  }

  /** 35°30'N, 97°15'W — mirrors inventory-impl's GpsJpeg test builder. */
  private static byte[] jpegWithGps() {
    java.io.ByteArrayOutputStream tiff = new java.io.ByteArrayOutputStream();
    tiff.write('I');
    tiff.write('I');
    u16(tiff, 42);
    u32(tiff, 8);
    u16(tiff, 1);
    u16(tiff, 0x8825);
    u16(tiff, 4);
    u32(tiff, 1);
    u32(tiff, 26);
    u32(tiff, 0);
    u16(tiff, 4);
    ascii(tiff, 0x0001, 'N');
    rat3(tiff, 0x0002, 80);
    ascii(tiff, 0x0003, 'W');
    rat3(tiff, 0x0004, 104);
    u32(tiff, 0);
    dms(tiff, 35, 30, 0);
    dms(tiff, 97, 15, 0);
    byte[] t = tiff.toByteArray();
    java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
    jpeg.write(0xFF);
    jpeg.write(0xD8);
    jpeg.write(0xFF);
    jpeg.write(0xE1);
    int len = 2 + 6 + t.length;
    jpeg.write(len >> 8);
    jpeg.write(len & 0xFF);
    jpeg.writeBytes("Exif".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    jpeg.write(0);
    jpeg.write(0);
    jpeg.writeBytes(t);
    jpeg.write(0xFF);
    jpeg.write(0xD9);
    return jpeg.toByteArray();
  }

  private static void ascii(java.io.ByteArrayOutputStream o, int tag, char ref) {
    u16(o, tag);
    u16(o, 2);
    u32(o, 2);
    o.write(ref);
    o.write(0);
    o.write(0);
    o.write(0);
  }

  private static void rat3(java.io.ByteArrayOutputStream o, int tag, int offset) {
    u16(o, tag);
    u16(o, 5);
    u32(o, 3);
    u32(o, offset);
  }

  private static void dms(java.io.ByteArrayOutputStream o, int deg, int min, int sec) {
    u32(o, deg);
    u32(o, 1);
    u32(o, min);
    u32(o, 1);
    u32(o, sec);
    u32(o, 1);
  }

  private static void u16(java.io.ByteArrayOutputStream o, int v) {
    o.write(v & 0xFF);
    o.write((v >> 8) & 0xFF);
  }

  private static void u32(java.io.ByteArrayOutputStream o, int v) {
    o.write(v & 0xFF);
    o.write((v >> 8) & 0xFF);
    o.write((v >> 16) & 0xFF);
    o.write((v >> 24) & 0xFF);
  }
}
