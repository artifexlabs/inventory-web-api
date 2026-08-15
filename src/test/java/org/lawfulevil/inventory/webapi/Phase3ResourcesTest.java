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
package org.lawfulevil.inventory.webapi;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

@QuarkusTest
public class Phase3ResourcesTest {
  private final static String TOKEN = "dev-token";

  private static io.restassured.specification.RequestSpecification authed() {
    return given().header("Authorization", "Bearer " + TOKEN).contentType(ContentType.JSON);
  }

  private static String createItem(String name) {
    return new JsonObject(authed().body(new JsonObject().put("name", name).put("type", "p3").encode())
        .post("/api/v1/items").then().statusCode(201).extract().asString()).getString("id");
  }

  @Test
  public void testPlaceIsAContainerWithCoordinates() {
    // Phase 15: a "location" is an item with coordinates that holds things
    String loc = new JsonObject(authed()
        .body(new JsonObject().put("name", "Garage").put("type", "location").encode())
        .post("/api/v1/items").then().statusCode(201).body("name", equalTo("Garage")).extract().asString())
        .getString("id");
    JsonObject pinned = new JsonObject(
        authed().get("/api/v1/items/" + loc).then().extract().asString())
        .put("latitude", 33.7).put("longitude", -84.4);
    authed().body(pinned.encode()).put("/api/v1/items/" + loc).then().statusCode(200)
        .body("latitude", is(33.7f));

    // contain an item in it: the item inherits the garage's pin
    String item = createItem("located-thing");
    authed().put("/api/v1/items/" + loc + "/contained/" + item).then().statusCode(204);
    authed().get("/api/v1/items/" + item + "/coordinates").then().statusCode(200)
        .body("latitude", is(33.7f)).body("longitude", is(-84.4f));

    // deleting the place orphans the item (no cascade, no reference guard)
    authed().delete("/api/v1/items/" + loc).then().statusCode(204);
    authed().get("/api/v1/items/" + item + "/container").then().statusCode(404);
    authed().get("/api/v1/items/" + item + "/coordinates").then().statusCode(404);
  }

  @Test
  public void testParValuesRideItemJson() {
    String item = createItem("par-thing");
    JsonObject full = new JsonObject(authed().get("/api/v1/items/" + item).then().extract().asString())
        .put("quantity", 2).put("parValues", new JsonObject().put("minOnHand", 5).put("maxOnHand", 50));
    authed().body(full.encode()).put("/api/v1/items/" + item).then().statusCode(200);
    authed().get("/api/v1/items/" + item).then().statusCode(200)
        .body("parValues.minOnHand", is(5)).body("parValues.maxOnHand", is(50));
  }

  @Test
  public void testAssetUploadDownloadDelete() {
    String item = createItem("asset-thing");
    byte[] photo = new byte[] { 9, 8, 7, 6 };

    String assetId = new JsonObject(given().header("Authorization", "Bearer " + TOKEN)
        .header(AssetsResource.FILENAME_HEADER, "pic.png").contentType("image/png").body(photo)
        .post("/api/v1/items/" + item + "/assets").then().statusCode(201)
        .body("filename", equalTo("pic.png")).body("sizeBytes", is(4)).extract().asString()).getString("id");

    authed().get("/api/v1/items/" + item + "/assets").then().statusCode(200).body("size()", is(1));

    byte[] roundTripped = given().header("Authorization", "Bearer " + TOKEN).get("/api/v1/assets/" + assetId)
        .then().statusCode(200).contentType("image/png")
        .header(AssetsResource.FILENAME_HEADER, "pic.png").extract().asByteArray();
    org.junit.jupiter.api.Assertions.assertArrayEquals(photo, roundTripped);

    // audit trail carries the attachment against the item
    authed().get("/api/v1/audit/target/" + item).then().statusCode(200)
        .body("action", org.hamcrest.Matchers.hasItem("asset.attach"));

    authed().delete("/api/v1/assets/" + assetId).then().statusCode(204);
    authed().delete("/api/v1/assets/" + assetId).then().statusCode(404);
    given().header("Authorization", "Bearer " + TOKEN)
        .header(AssetsResource.FILENAME_HEADER, "f").contentType("image/png").body(photo)
        .post("/api/v1/items/no-such-item/assets").then().statusCode(404);
  }
}
