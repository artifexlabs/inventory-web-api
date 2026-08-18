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

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

/**
 * {@code inventory.catalog=off}: lookups answer 503, but the catalog is
 * prefill — creation from the request body alone still works.
 */
@QuarkusTest
@TestProfile(CatalogOffTest.OffProfile.class)
public class CatalogOffTest {

  public static class OffProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("inventory.catalog", "off");
    }
  }

  private final static String TOKEN = "dev-token";

  @Test
  public void testOffDisablesLookupsButNeverCreation() {
    given().header("Authorization", "Bearer " + TOKEN)
        .get("/api/v1/catalog/upc/0049000006346").then().statusCode(503);

    String body = given().header("Authorization", "Bearer " + TOKEN).contentType(ContentType.JSON)
        .body(new JsonObject().put("name", "offline-widget").put("type", "tool").encode())
        .post("/api/v1/items/from-upc?gtin=0049000006346").then().statusCode(201)
        .body("item.name", equalTo("offline-widget")).extract().asString();
    String itemId = new JsonObject(body).getJsonObject("item").getString("id");
    given().header("Authorization", "Bearer " + TOKEN)
        .get("/api/v1/items/by-identity?kind=upc&value=0049000006346").then().statusCode(200)
        .body("id", equalTo(itemId));
  }
}
