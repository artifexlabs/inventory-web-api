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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import org.junit.jupiter.api.Test;

/**
 * The manifest text formats. Worth pinning precisely because a parser that mis-reads is far worse than one that
 * refuses: a silently truncated manifest claims a medium holds less than it does, and every duplicate-section answer
 * built on it is then confidently wrong.
 */
public class ManifestFormatTest {

  private static JsonObject first(JsonArray a) {
    return a.getJsonObject(0);
  }

  @Test
  public void findLinesCarryRealSizesWhichIsTheWholePoint() {
    JsonArray parsed = DataResource.parseManifestText("1024\t2026-08-27T10:00:00\tphotos/a.jpg");
    assertEquals(1, parsed.size());
    assertEquals(1024L, first(parsed).getLong("sizeBytes"),
        "sha256sum ingestion always wrote 0, which silently disables the size floor");
    assertEquals("photos/a.jpg", first(parsed).getString("path"));
    assertNull(first(parsed).getString("hash"), "a find manifest describes; it does not hash");
  }

  @Test
  public void pathsWithSpacesSurviveBecauseSplittingIsOnTabs() {
    JsonArray parsed = DataResource.parseManifestText("10\t2026-08-27T10:00:00\tMy Documents/a file with spaces.txt");
    assertEquals("My Documents/a file with spaces.txt", first(parsed).getString("path"),
        "a media tree is full of spaces; splitting on whitespace would shred it");
  }

  @Test
  public void anEmptyDirectoryLineIsAccepted() {
    JsonArray parsed = DataResource.parseManifestText("\t\tsome/empty/dir/");
    assertEquals("some/empty/dir/", first(parsed).getString("path"),
        "the trailing slash is the marker; without it an empty directory cannot be named at all");
    assertEquals(0L, first(parsed).getLong("sizeBytes"));
  }

  @Test
  public void sha256sumStillWorksAndIsChosenByShape() {
    String digest = "a".repeat(64);
    JsonArray parsed = DataResource.parseManifestText(digest + "  docs/readme.txt");
    assertEquals(digest, first(parsed).getString("hash"));
    assertEquals("docs/readme.txt", first(parsed).getString("path"),
        "a checksum file someone already has is still a legitimate way to describe a disc");
  }

  @Test
  public void theTwoFormatsCannotBeConfused() {
    // a find line always has tabs; a sha256sum line never does
    assertTrue(DataResource.parseManifestText("5\t\tx.bin").getJsonObject(0).getLong("sizeBytes") == 5L);
    assertEquals(64,
        DataResource.parseManifestText("b".repeat(64) + "  x.bin").getJsonObject(0).getString("hash").length());
  }

  @Test
  public void malformedLinesAreRefusedByNumberNotSkipped() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> DataResource.parseManifestText("10\t2026-08-27T10:00:00\tgood.txt\nnot-a-find-line\tonly-two"));
    assertTrue(e.getMessage().contains("line 2"), () -> "should name the offending line: " + e.getMessage());
  }

  @Test
  public void anUnreadableSizeIsRefusedRatherThanTreatedAsZero() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> DataResource.parseManifestText("banana\t2026-08-27T10:00:00\tx.bin"));
    assertTrue(e.getMessage().contains("size"), () -> e.getMessage());
  }

  @Test
  public void mtimeIsCarriedBecauseHashPreservationDependsOnIt() {
    JsonArray parsed = DataResource.parseManifestText("7\t2026-08-27T10:30:00\tx.bin");
    assertTrue(first(parsed).getString("modifiedAt").startsWith("2026-08-27T10:30:00"),
        "re-describing a medium keeps a hash only when (path, size, mtime) all match");
  }
}
