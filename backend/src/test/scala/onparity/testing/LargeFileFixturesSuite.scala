package onparity.testing

import java.nio.file.Files

class LargeFileFixturesSuite extends munit.FunSuite:

  test("journalFile generates the requested number of id,amount rows"):
    val file  = LargeFileFixtures.journalFile(rows = 5)
    val lines = Files.readAllLines(file.toPath)
    assertEquals(lines.size(), 5)
    assertEquals(lines.get(0), "1,101.00")
    assertEquals(lines.get(4), "5,105.00")

  test("reportFile produces the same id,amount rows as journalFile for the same row count"):
    val journalLines = Files.readAllLines(LargeFileFixtures.journalFile(rows = 10).toPath)
    val reportLines  = Files.readAllLines(LargeFileFixtures.reportFile(rows  = 10).toPath)
    assertEquals(journalLines, reportLines)

  test("a cached file is not regenerated on a second call"):
    val first         = LargeFileFixtures.journalFile(rows = 3)
    val firstModified = first.lastModified()
    val second        = LargeFileFixtures.journalFile(rows = 3)
    assertEquals(second.lastModified(), firstModified)
