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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
public class QrPagesTest {

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
  public void testDeepLinkRedirectsToItem() {
    // the scan target itself needs no session; the item page it lands on does
    given().redirects().follow(false).get("/i/wrench-1").then().statusCode(303).header("Location",
        containsString("/items/wrench-1"));
  }

  @Test
  public void testQrImageProxiedAndOnItemPage() {
    String s = login();
    given().cookie("inv_session", s).get("/items/wrench-1").then().statusCode(200)
        .body(containsString("/items/wrench-1/qr.png")).body(containsString("Print label"));
    byte[] png = given().cookie("inv_session", s).get("/items/wrench-1/qr.png").then().statusCode(200)
        .contentType("image/png").extract().asByteArray();
    Assertions.assertEquals((byte) 0x89, png[0]);
    given().cookie("inv_session", s).get("/items/no-such/qr.png").then().statusCode(404);
  }

  @Test
  public void testPrintLabelFlow() {
    String s = login();
    given().redirects().follow(false).cookie("inv_session", s).post("/items/wrench-1/print-label").then()
        .statusCode(303).header("Location", containsString("/items/wrench-1"));
    Assertions.assertEquals(java.util.List.of("wrench-1"), this.stub.printedLabels());
  }
}
