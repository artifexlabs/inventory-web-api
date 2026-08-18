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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

/**
 * The views aggregate real domain state since the HTTP consolidation, so this
 * test builds its fixture through the public API (own test profile = own
 * Quarkus instance = clean in-memory store) and asserts the shaped output.
 */
@QuarkusTest
@TestProfile(ItemViewsResourceTest.Profile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ItemViewsResourceTest {
  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("inventory.views.test", "true");
    }
  }

  private static String wrenchId;
  private static String boxId;
  private static String shelfId;

  private static String create(String name, String type) {
    String body = given().header("Authorization", "Bearer dev-token").contentType(ContentType.JSON)
        .body(new JsonObject().put("name", name).put("type", type).encode()).post("/api/v1/items")
        .then().statusCode(201).extract().asString();
    return new JsonObject(body).getString("id");
  }

  private static JsonObject fetch(String id) {
    return new JsonObject(given().header("Authorization", "Bearer dev-token").get("/api/v1/items/" + id)
        .then().statusCode(200).extract().asString());
  }

  private static void update(JsonObject item) {
    given().header("Authorization", "Bearer dev-token").contentType(ContentType.JSON).body(item.encode())
        .put("/api/v1/items/" + item.getString("id")).then().statusCode(200);
  }

  @Test
  @Order(1)
  void fixture() {
    wrenchId = create("wrench", "tool");
    boxId = create("toolbox", "container");
    shelfId = create("shelf", "furniture");

    // wrench runs below its par minimum -> belowMin appears in the listing
    JsonObject wrench = fetch(wrenchId);
    wrench.put("quantity", 1).put("parValues", new JsonObject().put("minOnHand", 5).put("maxOnHand", 50));
    update(wrench);

    // pin the shelf: the detail view derives placement from containment
    JsonObject shelf = fetch(shelfId);
    shelf.put("latitude", 33.7).put("longitude", -84.4);
    update(shelf);
    JsonObject box = fetch(boxId);
    box.put("description", "red toolbox");
    update(box); // also the most recent audit action for the box: item.update

    // containment: shelf > box > wrench
    given().header("Authorization", "Bearer dev-token").put("/api/v1/items/" + boxId + "/contained/" + wrenchId)
        .then().statusCode(204);
    given().header("Authorization", "Bearer dev-token").put("/api/v1/items/" + shelfId + "/contained/" + boxId)
        .then().statusCode(204);

    // one asset on the box
    given().header("Authorization", "Bearer dev-token").header("X-Filename", "photo.png")
        .contentType("image/png").body(new byte[] { 1, 2, 3 }).post("/api/v1/items/" + boxId + "/assets")
        .then().statusCode(201);
  }

  @Test
  @Order(2)
  void listingReturnsAllWithDerivedBelowMin() {
    given().header("Authorization", "Bearer dev-token").get("/api/v1/views/items").then().statusCode(200)
        .body("total", equalTo(3)).body("items", hasSize(3)).body("page", equalTo(0))
        .body("items.find { it.id == '" + wrenchId + "' }.belowMin", equalTo(true))
        .body("items.find { it.id == '" + boxId + "' }.belowMin", nullValue());
  }

  @Test
  @Order(2)
  void viewsCarryTypeSuggestions() {
    // distinct in-use types + the conventional trio, sorted, no "_" filler —
    // present on the listing AND the detail so every form can offer them
    given().header("Authorization", "Bearer dev-token").get("/api/v1/views/items").then().statusCode(200)
        .body("types", hasItem("tool")).body("types", hasItem("furniture"))
        .body("types", hasItem("container")).body("types", hasItem("location"))
        .body("types", hasItem("thing")).body("types", not(hasItem("_")));
    given().header("Authorization", "Bearer dev-token").get("/api/v1/views/items/" + boxId + "/detail").then()
        .statusCode(200).body("types", hasItem("tool")).body("types", not(hasItem("_")));
  }

  @Test
  @Order(3)
  void listingFiltersByQueryAcrossNameDisplayNameAndType() {
    given().header("Authorization", "Bearer dev-token").get("/api/v1/views/items?query=tool").then()
        .statusCode(200).body("total", equalTo(2)).body("items.id", hasItem(wrenchId))
        .body("items.id", hasItem(boxId)).body("items.id", not(hasItem(shelfId)));
  }

  @Test
  @Order(4)
  void listingPaginates() {
    given().header("Authorization", "Bearer dev-token").get("/api/v1/views/items?page=1&size=2").then()
        .statusCode(200).body("total", equalTo(3)).body("items", hasSize(1)).body("page", equalTo(1))
        .body("size", equalTo(2));
  }

  @Test
  @Order(5)
  void detailAggregatesEverySectionInOneCall() {
    given().header("Authorization", "Bearer dev-token").get("/api/v1/views/items/" + boxId + "/detail").then()
        .statusCode(200)
        .body("item.name", equalTo("toolbox"))
        .body("children[0].id", equalTo(wrenchId))
        .body("container.id", equalTo(shelfId))
        .body("candidates.id", hasItem(wrenchId))
        .body("candidates.id", not(hasItem(boxId)))
        .body("history.action", hasItem("item.update"))
        .body("assets[0].filename", equalTo("photo.png"))
        .body("locationName", equalTo("shelf"))
        .body("effectiveCoordinates.latitude", equalTo(33.7f));
  }

  @Test
  @Order(6)
  void detailOfUnknownItemIs404() {
    given().header("Authorization", "Bearer dev-token").get("/api/v1/views/items/missing/detail").then()
        .statusCode(404);
  }

  @Test
  @Order(7)
  void badTokenIs401OnBothViews() {
    given().header("Authorization", "Bearer bad").get("/api/v1/views/items").then().statusCode(401);
    given().header("Authorization", "Bearer bad").get("/api/v1/views/items/" + boxId + "/detail").then()
        .statusCode(401);
  }
}
