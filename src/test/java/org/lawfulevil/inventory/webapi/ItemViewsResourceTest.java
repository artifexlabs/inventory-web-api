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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(StubBackend.class)
class ItemViewsResourceTest {

  @Test
  void listingReturnsAllWithDerivedBelowMin() {
    given().header("Authorization", "Bearer tkn").get("/api/v1/views/items").then().statusCode(200)
        .body("total", equalTo(3)).body("items", hasSize(3)).body("page", equalTo(0))
        .body("items.find { it.id == 'w-1' }.belowMin", equalTo(true))
        .body("items.find { it.id == 'box-1' }.belowMin", nullValue());
  }

  @Test
  void listingFiltersByQueryAcrossNameDisplayNameAndType() {
    given().header("Authorization", "Bearer tkn").get("/api/v1/views/items?query=tool").then().statusCode(200)
        .body("total", equalTo(2)).body("items.id", hasItem("w-1")).body("items.id", hasItem("box-1"))
        .body("items.id", not(hasItem("shelf-1")));
  }

  @Test
  void listingPaginates() {
    given().header("Authorization", "Bearer tkn").get("/api/v1/views/items?page=1&size=2").then().statusCode(200)
        .body("total", equalTo(3)).body("items", hasSize(1)).body("page", equalTo(1)).body("size", equalTo(2));
  }

  @Test
  void detailAggregatesEverySectionInOneCall() {
    given().header("Authorization", "Bearer tkn").get("/api/v1/views/items/box-1/detail").then().statusCode(200)
        .body("item.name", equalTo("toolbox"))
        .body("children[0].id", equalTo("w-1"))
        .body("containers[0].id", equalTo("shelf-1"))
        .body("candidates.id", hasItem("w-1"))
        .body("candidates.id", not(hasItem("box-1")))
        .body("history[0].action", equalTo("item.update"))
        .body("locations[0].name", equalTo("Garage"))
        .body("assets[0].filename", equalTo("photo.png"))
        .body("locationName", equalTo("Garage"));
  }

  @Test
  void detailOfUnknownItemIs404() {
    given().header("Authorization", "Bearer tkn").get("/api/v1/views/items/missing/detail").then().statusCode(404);
  }

  @Test
  void badTokenIs401OnBothViews() {
    given().header("Authorization", "Bearer bad").get("/api/v1/views/items").then().statusCode(401);
    given().header("Authorization", "Bearer bad").get("/api/v1/views/items/box-1/detail").then().statusCode(401);
  }
}
