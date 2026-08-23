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
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;

/**
 * The whole scanned-barcode flow against the stub catalog: prefill lookup, one-shot creation with identity + tags +
 * downloaded image, conflict on a reused code, and creation surviving a catalog miss.
 */
@QuarkusTest
@TestProfile(CatalogApiTest.CatalogProfile.class)
@QuarkusTestResource(value = CatalogStubResource.class, restrictToAnnotatedClass = true)
public class CatalogApiTest {

  /** Dedicated app instance so the stub catalog config cannot bleed. */
  public static class CatalogProfile implements QuarkusTestProfile {
  }

  private final static String TOKEN = "dev-token";
  /** valid check digit, unknown to the stub */
  private final static String UNKNOWN_GTIN = "4006381333931";

  private static io.restassured.specification.RequestSpecification authed() {
    return given().header("Authorization", "Bearer " + TOKEN);
  }

  @Test
  public void testLookupPrefillsFromTheCatalog() {
    authed().get("/api/v1/catalog/upc/" + CatalogStubResource.KNOWN_GTIN).then().statusCode(200)
        .body("name", equalTo("Stub Cola")).body("brand", equalTo("StubCo")).body("category", equalTo("test-drinks"))
        .body("weightGrams", equalTo(355.0f)).body("sourceUrl", notNullValue());
    // UPC-A form of the same code canonicalizes and still hits
    authed().get("/api/v1/catalog/upc/049000006346").then().statusCode(200).body("name", equalTo("Stub Cola"));
  }

  @Test
  public void testLookupMissAndBadDigit() {
    authed().get("/api/v1/catalog/upc/" + UNKNOWN_GTIN).then().statusCode(404);
    authed().get("/api/v1/catalog/upc/049000006347").then().statusCode(400);
    authed().get("/api/v1/catalog/upc/not-a-upc").then().statusCode(400);
    given().get("/api/v1/catalog/upc/" + CatalogStubResource.KNOWN_GTIN).then().statusCode(401);
  }

  @Test
  public void testFromUpcCreatesTheFullItem() {
    String body = authed().contentType(ContentType.JSON).body(new JsonObject().put("type", "drink").encode())
        .post("/api/v1/items/from-upc?gtin=" + CatalogStubResource.KNOWN_GTIN).then().statusCode(201)
        .body("item.name", equalTo("Stub Cola")).body("item.type", equalTo("drink")).body("asset.id", notNullValue())
        .extract().asString();
    JsonObject made = new JsonObject(body);
    String itemId = made.getJsonObject("item").getString("id");

    // the scanned code resolves back; tags carry brand/category/source
    authed().get("/api/v1/items/by-identity?kind=upc&value=" + CatalogStubResource.KNOWN_GTIN).then().statusCode(200)
        .body("id", equalTo(itemId));
    authed().get("/api/v1/items/" + itemId).then().statusCode(200).body("tags.key", hasItem("brand"))
        .body("tags.key", hasItem("category")).body("tags.key", hasItem("source"))
        .body("description", equalTo("A canned test beverage"));
    // the downloaded catalog image is a real asset
    byte[] image = authed().get("/api/v1/assets/" + made.getJsonObject("asset").getString("id")).then().statusCode(200)
        .extract().asByteArray();
    org.junit.jupiter.api.Assertions.assertArrayEquals(CatalogStubResource.IMAGE, image);

    // a reused code is a conflict, and the item did not move
    authed().contentType(ContentType.JSON).body("{}")
        .post("/api/v1/items/from-upc?gtin=" + CatalogStubResource.KNOWN_GTIN).then().statusCode(409);
    authed().get("/api/v1/items/by-identity?kind=upc&value=" + CatalogStubResource.KNOWN_GTIN).then().statusCode(200)
        .body("id", equalTo(itemId));
  }

  @Test
  public void testCatalogMissStillCreatesFromTheBody() {
    String container = new JsonObject(authed().contentType(ContentType.JSON)
        .body(new JsonObject().put("name", "upc-shelf").put("type", "container").encode()).post("/api/v1/items").then()
        .statusCode(201).extract().asString()).getString("id");

    authed().contentType(ContentType.JSON).body(new JsonObject().put("name", "mystery-widget").encode())
        .post("/api/v1/items/from-upc?gtin=" + UNKNOWN_GTIN + "&container=" + container).then().statusCode(201)
        .body("item.name", equalTo("mystery-widget")).body("item.type", equalTo("thing"))
        .body("item.containerId", equalTo(container)).body("asset", org.hamcrest.Matchers.nullValue());

    // no name from either side: nothing to create
    authed().contentType(ContentType.JSON).body("{}").post("/api/v1/items/from-upc?gtin=0000096385074").then()
        .statusCode(400);
    // unknown container refuses the whole creation
    authed().contentType(ContentType.JSON).body(new JsonObject().put("name", "x").encode())
        .post("/api/v1/items/from-upc?gtin=0000096385074&container=missing").then().statusCode(404);
    // bad check digit refuses before anything else
    authed().contentType(ContentType.JSON).body(new JsonObject().put("name", "x").encode())
        .post("/api/v1/items/from-upc?gtin=049000006347").then().statusCode(400);
  }
}
