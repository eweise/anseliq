package perfectpair.testing

import java.io.{BufferedWriter, File, FileWriter}

object LargeFileFixtures:
  private val cacheDir = File("target/large-fixtures")

  def journalFile(rows: Int = 5_000_000): File = cachedFile(s"journal-$rows.csv", rows)

  def reportFile(rows: Int = 5_000_000): File = cachedFile(s"report-$rows.csv", rows)

  private def cachedFile(fileName: String, rows: Int): File =
    val file = File(cacheDir, fileName)
    if !file.exists() then
      cacheDir.mkdirs()
      val writer = BufferedWriter(FileWriter(file), 1 << 20)
      try
        var i = 1
        while i <= rows do
          val amount = 100 + (i % 900)
          writer.write(s"$i,$amount.00")
          writer.newLine()
          i += 1
      finally writer.close()
    file
