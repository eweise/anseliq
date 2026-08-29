package onparity.common.model

import org.scalatest.funsuite.AnyFunSuite
import upickle.default.*

class DataSourceConfigSuite extends AnyFunSuite:

  test("DataSourceConfig with CSVParserConfig round-trips through upickle") {
    val cfg =
      DataSourceConfig(CSVParserConfig(hasHeader = true, columnDefinitions = Map(0 -> "id"), amountField = Some(1)))
    val json = write(cfg)
    assertResult(cfg)(read[DataSourceConfig](json))
  }

  test("DataSourceConfig with BAIParserConfig round-trips through upickle") {
    val cfg  = DataSourceConfig(BAIParserConfig(fieldMappings = Map(BAIField.AccountNumber -> "acct")))
    val json = write(cfg)
    assertResult(cfg)(read[DataSourceConfig](json))
  }

  test("DataSourceConfig with JSONParserConfig round-trips through upickle") {
    val cfg  = DataSourceConfig(JSONParserConfig(fieldMappings = Map("$.a" -> "a"), amountPath = Some("$.amt")))
    val json = write(cfg)
    info(s"serialized: $json")
    assertResult(cfg)(read[DataSourceConfig](json))
  }

  test("FixedWidthParserConfig.validate is empty for a valid config") {
    val cfg = FixedWidthParserConfig(
      fields = Seq(
        FixedWidthField("id", 1, 10, isAmount = true),
        FixedWidthField("name", 11, 20)
      )
    )
    assertResult(Nil)(cfg.validate)
  }

  test("FixedWidthParserConfig.validate rejects fewer than two fields") {
    val cfg = FixedWidthParserConfig(fields = Seq(FixedWidthField("id", 1, 10, isAmount = true)))
    assert(cfg.validate.contains("At least two fields must be defined"))
  }

  test("FixedWidthParserConfig.validate rejects no fields") {
    val cfg = FixedWidthParserConfig(fields = Seq.empty)
    assertResult(List("At least two fields must be defined"))(cfg.validate)
  }

  test("FixedWidthParserConfig.validate rejects a config with no amount field selected") {
    val cfg = FixedWidthParserConfig(
      fields = Seq(FixedWidthField("id", 1, 10), FixedWidthField("name", 11, 20))
    )
    assert(cfg.validate.contains("One field must be marked as the amount field"))
  }

  test("FixedWidthParserConfig.validate rejects a field whose end position is before its start position") {
    val cfg = FixedWidthParserConfig(
      fields = Seq(
        FixedWidthField("id", 1, 10, isAmount = true),
        FixedWidthField("name", 20, 15)
      )
    )
    assert(cfg.validate.contains("Field 'name': end position must not be less than start position"))
  }

  test("FixedWidthParserConfig.validate rejects more than one field marked as the amount field") {
    val cfg = FixedWidthParserConfig(
      fields = Seq(
        FixedWidthField("a", 1, 10, isAmount  = true),
        FixedWidthField("b", 11, 20, isAmount = true)
      )
    )
    assert(cfg.validate.contains("Only one field may be marked as the amount field"))
  }

  test("FixedWidthParserConfig.validate rejects a blank field name") {
    val cfg = FixedWidthParserConfig(
      fields = Seq(FixedWidthField("", 1, 10, isAmount = true), FixedWidthField("b", 11, 20))
    )
    assert(cfg.validate.contains("Every field must have a name"))
  }

  test("FixedWidthParserConfig.validate rejects duplicate field names") {
    val cfg = FixedWidthParserConfig(
      fields = Seq(
        FixedWidthField("dup", 1, 10, isAmount = true),
        FixedWidthField("dup", 11, 20)
      )
    )
    assert(cfg.validate.contains("Field name(s) used more than once: dup"))
  }

  test("FixedWidthParserConfig.validate rejects a start position less than 1") {
    val cfg = FixedWidthParserConfig(
      fields = Seq(FixedWidthField("a", 0, 10, isAmount = true), FixedWidthField("b", 11, 20))
    )
    assert(cfg.validate.contains("Start position must be at least 1 (positions are 1-based)"))
  }

  test("FixedWidthParserConfig.validate rejects overlapping field positions") {
    val cfg = FixedWidthParserConfig(
      fields = Seq(
        FixedWidthField("a", 1, 10, isAmount = true),
        FixedWidthField("b", 5, 15)
      )
    )
    assert(cfg.validate.contains("Fields 'a' and 'b' have overlapping positions"))
  }

  test("FixedWidthParserConfig.validate allows adjacent, non-overlapping fields") {
    val cfg = FixedWidthParserConfig(
      fields = Seq(
        FixedWidthField("a", 1, 10, isAmount = true),
        FixedWidthField("b", 11, 20)
      )
    )
    assertResult(Nil)(cfg.validate)
  }

  test("FixedWidthParserConfig.validate does not report a spurious overlap against an already-malformed field") {
    val cfg = FixedWidthParserConfig(
      fields = Seq(
        FixedWidthField("a", 1, 10, isAmount = true),
        FixedWidthField("b", 20, 15)
      )
    )
    assert(!cfg.validate.exists(_.contains("overlapping")))
  }

  test("CSVParserConfig.validate rejects an empty column list") {
    val cfg = CSVParserConfig(hasHeader = true, columnDefinitions = Map.empty, amountField = None)
    assert(cfg.validate.contains("At least one column must be defined"))
  }

  test("CSVParserConfig.validate rejects a negative column number") {
    val cfg = CSVParserConfig(hasHeader = true, columnDefinitions = Map(-1 -> "id"), amountField = None)
    assert(cfg.validate.contains("Column numbers must not be negative"))
  }

  test("CSVParserConfig.validate rejects a blank field name") {
    val cfg = CSVParserConfig(hasHeader = true, columnDefinitions = Map(0 -> ""), amountField = None)
    assert(cfg.validate.contains("Every column must have a field name"))
  }

  test("CSVParserConfig.validate rejects duplicate field names") {
    val cfg = CSVParserConfig(hasHeader = true, columnDefinitions = Map(0 -> "dup", 1 -> "dup"), amountField = None)
    assert(cfg.validate.contains("Field name(s) used more than once: dup"))
  }

  test("CSVParserConfig.validate rejects a negative amount column") {
    val cfg = CSVParserConfig(hasHeader = true, columnDefinitions = Map(0 -> "id"), amountField = Some(-1))
    assert(cfg.validate.contains("Amount column number must not be negative"))
  }

  test("CSVParserConfig.validate is empty for a valid config") {
    val cfg =
      CSVParserConfig(hasHeader = true, columnDefinitions = Map(0 -> "id", 1 -> "amount"), amountField = Some(1))
    assertResult(Nil)(cfg.validate)
  }

  test("BAIParserConfig.validate rejects a blank stored-as name") {
    val cfg = BAIParserConfig(fieldMappings = Map(BAIField.AccountNumber -> ""))
    assert(cfg.validate.contains("Every included BAI2 field must have a name to store it under"))
  }

  test("BAIParserConfig.validate rejects duplicate stored-as names") {
    val cfg = BAIParserConfig(
      fieldMappings = Map(BAIField.AccountNumber -> "dup", BAIField.TypeCode -> "dup")
    )
    assert(cfg.validate.contains("Field name(s) used more than once: dup"))
  }

  test("BAIParserConfig.validate allows an empty fieldMappings (amount-only capture is a legitimate use case)") {
    assertResult(Nil)(BAIParserConfig(fieldMappings = Map.empty).validate)
  }

  test("JSONParserConfig.validate rejects an empty field list") {
    assert(JSONParserConfig().validate.contains("At least one field must be defined"))
  }

  test("JSONParserConfig.validate rejects a blank JSON path") {
    val cfg = JSONParserConfig(fieldMappings = Map("" -> "name"))
    assert(cfg.validate.contains("Every field must have a JSON path"))
  }

  test("JSONParserConfig.validate rejects a blank stored name") {
    val cfg = JSONParserConfig(fieldMappings = Map("$.a" -> ""))
    assert(cfg.validate.contains("Every field must have a stored name"))
  }

  test("JSONParserConfig.validate rejects duplicate stored names") {
    val cfg = JSONParserConfig(fieldMappings = Map("$.a" -> "dup", "$.b" -> "dup"))
    assert(cfg.validate.contains("Field name(s) used more than once: dup"))
  }

  test("JSONParserConfig.validate rejects a blank amount path") {
    val cfg = JSONParserConfig(fieldMappings = Map("$.a" -> "a"), amountPath = Some(""))
    assert(cfg.validate.contains("Amount path must not be blank"))
  }

  test("JSONParserConfig.validate is empty for a valid config") {
    val cfg = JSONParserConfig(fieldMappings = Map("$.a" -> "a"), amountPath = Some("$.amt"))
    assertResult(Nil)(cfg.validate)
  }

  test("FixedWidthParserConfig.validate can report multiple errors at once") {
    val cfg = FixedWidthParserConfig(fields = Seq(FixedWidthField("name", 20, 15)))
    assertResult(
      List(
        "At least two fields must be defined",
        "One field must be marked as the amount field",
        "Field 'name': end position must not be less than start position"
      )
    )(cfg.validate)
  }

  test("DataSourceConfig.validate delegates to the parser config's validate") {
    val cfg = DataSourceConfig(FixedWidthParserConfig(fields = Seq(FixedWidthField("id", 1, 10, isAmount = true))))
    assertResult(List("At least two fields must be defined"))(cfg.validate)
  }

  test("DataSourceConfig.validate delegates to a CSVParserConfig's own validate") {
    val cfg = DataSourceConfig(CSVParserConfig(hasHeader = true, columnDefinitions = Map.empty, amountField = Some(0)))
    assertResult(List("At least one column must be defined"))(cfg.validate)
  }

  test("CSVParserConfig.validate rejects a config with no amount column selected") {
    val cfg = CSVParserConfig(hasHeader = true, columnDefinitions = Map(0 -> "id"), amountField = None)
    assert(cfg.validate.contains("One column must be marked as the amount field"))
  }

  test("JSONParserConfig.validate rejects a config with no amount path selected") {
    val cfg = JSONParserConfig(fieldMappings = Map("$.a" -> "a"), amountPath = None)
    assert(cfg.validate.contains("One field must be marked as the amount field"))
  }
