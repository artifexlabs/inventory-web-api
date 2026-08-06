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

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
public class Phase3PagesTest {

  @Inject
  StubServerClient stub;

  @BeforeEach
  public void reset() {
    this.stub.reset();
  }

  private String login() {
    return given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
        .formParam("email", StubServerClient.EMAIL).formParam("password", StubServerClient.PASSWORD)
        .post("/login").then().statusCode(303).extract().cookie("inv_session");
  }

  @Test
  public void testLocationsPageCreateAndDelete() {
    String s = login();
    given().redirects().follow(false).cookie("inv_session", s)
        .contentType("application/x-www-form-urlencoded").formParam("name", "Garage")
        .formParam("latitude", "33.7").formParam("longitude", "-84.4").post("/locations/create").then()
        .statusCode(303);
    given().cookie("inv_session", s).get("/locations").then().statusCode(200).body(containsString("Garage"))
        .body(containsString("33.7"));

    String locId = this.stub.locations(StubServerClient.TOKEN).get(0).getString("id");
    given().redirects().follow(false).cookie("inv_session", s).post("/locations/" + locId + "/delete").then()
        .statusCode(303);
    given().cookie("inv_session", s).get("/locations").then().statusCode(200)
        .body(not(containsString("Garage")));
  }

  @Test
  public void testItemEditWithLocationAndParValues() {
    String s = login();
    given().redirects().follow(false).cookie("inv_session", s)
        .contentType("application/x-www-form-urlencoded").formParam("name", "Shed")
        .post("/locations/create").then().statusCode(303);
    String locId = this.stub.locations(StubServerClient.TOKEN).get(0).getString("id");

    given().redirects().follow(false).cookie("inv_session", s)
        .contentType("application/x-www-form-urlencoded").formParam("name", "wrench")
        .formParam("type", "tool").formParam("quantity", "2").formParam("locationId", locId)
        .formParam("minOnHand", "5").formParam("maxOnHand", "10").post("/items/wrench-1/edit").then()
        .statusCode(303);

    // detail shows location name and par values; items list flags low stock
    given().cookie("inv_session", s).get("/items/wrench-1").then().statusCode(200)
        .body(containsString("Shed")).body(containsString("5 / 10"));
    given().cookie("inv_session", s).get("/items").then().statusCode(200).body(containsString("Low"));

    // referenced location refuses deletion in the stub too
    Assertions.assertFalse(this.stub.deleteLocation(StubServerClient.TOKEN, locId));
  }

  @Test
  public void testAssetUploadViewDelete() {
    String s = login();
    byte[] png = new byte[] { (byte) 0x89, 'P', 'N', 'G' };

    given().redirects().follow(false).cookie("inv_session", s).contentType("multipart/form-data")
        .multiPart("file", "photo.png", png, "image/png").post("/items/wrench-1/assets/upload").then()
        .statusCode(303).header("Location", containsString("/items/wrench-1"));

    // detail lists the asset with an inline img tag; bytes proxy through the webapp
    given().cookie("inv_session", s).get("/items/wrench-1").then().statusCode(200)
        .body(containsString("photo.png")).body(containsString("img src=\"/assets/"));
    String assetId = this.stub.assetsFor(StubServerClient.TOKEN, "wrench-1").get(0).getString("id");
    byte[] served = given().cookie("inv_session", s).get("/assets/" + assetId).then().statusCode(200)
        .contentType("image/png").extract().asByteArray();
    Assertions.assertArrayEquals(png, served);

    given().redirects().follow(false).cookie("inv_session", s)
        .contentType("application/x-www-form-urlencoded").formParam("itemId", "wrench-1")
        .post("/assets/" + assetId + "/delete").then().statusCode(303);
    given().cookie("inv_session", s).get("/items/wrench-1").then().statusCode(200)
        .body(not(containsString("photo.png")));
  }
}
