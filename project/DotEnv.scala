import sbt._

/**
 * The NAME=value lines of the project's .env (see scripts/dev-env.sh), for the backend run from
 * sbt, so it gets its settings without the shell that started sbt having loaded them. A variable
 * already set in that shell wins over .env.
 */
object DotEnv {

  def load(file: File): Map[String, String] =
    if (!file.exists) Map.empty
    else
      IO.readLines(file)
        .map(_.trim)
        .filter(line => line.nonEmpty && !line.startsWith("#") && line.contains("="))
        .map { line =>
          val (name, value) = line.splitAt(line.indexOf('='))
          name.trim -> unquote(value.drop(1).trim)
        }
        .filterNot { case (name, _) => sys.env.contains(name) }
        .toMap

  private def unquote(value: String): String =
    if (value.length >= 2 && (value.startsWith("\"") || value.startsWith("'")) && value.last == value.head)
      value.substring(1, value.length - 1)
    else value
}
