package perfectpair.view.components

import perfectpair.common.model.{CSVParserConfig, DataSourceConfig}
import tyrian.EmptyAttribute
import tyrian.Html.*
import tyrian.Html

object DataSourceConfigWidget:

  case class Model(
    enabled:        Boolean      = false,
    hasHeader:      Boolean      = true,
    columns:        List[String] = List(),
    amountColIndex: Option[Int]  = None
  )

  enum Msg:
    case Toggle(enabled: Boolean)
    case SetHasHeader(value: Boolean)
    case AddColumn
    case UpdateColumn(index: Int, name: String)
    case RemoveColumn(index: Int)
    case SetAmountCol(index: Int)

  def update(model: Model, msg: Msg): Model =
    msg match
      case Msg.Toggle(false)      => Model()
      case Msg.Toggle(true)       => model.copy(enabled = true)
      case Msg.SetHasHeader(v)    => model.copy(hasHeader = v)
      case Msg.AddColumn          => model.copy(columns = model.columns :+ "")
      case Msg.UpdateColumn(i, v) => model.copy(columns = model.columns.updated(i, v))
      case Msg.SetAmountCol(i)    => model.copy(amountColIndex = Some(i))
      case Msg.RemoveColumn(i)    =>
        val newCols = model.columns.patch(i, Nil, 1)
        val newAmt  = model.amountColIndex.flatMap { idx =>
          if idx == i then None
          else if idx > i then Some(idx - 1)
          else Some(idx)
        }
        model.copy(columns = newCols, amountColIndex = newAmt)

  def toDataSourceConfig(m: Model): Option[DataSourceConfig] =
    if !m.enabled then None
    else Some(DataSourceConfig(
      parserConfig = CSVParserConfig(
        hasHeader         = m.hasHeader,
        columnDefinitions = m.columns.zipWithIndex.map((name, i) => i -> name).toMap,
        amountField       = m.amountColIndex
      )
    ))

  def fromDataSourceConfig(cfg: Option[DataSourceConfig]): Model =
    cfg match
      case None => Model()
      case Some(DataSourceConfig(CSVParserConfig(hasHeader, colDefs, amountField))) =>
        Model(
          enabled        = true,
          hasHeader      = hasHeader,
          columns        = colDefs.toList.sortBy(_._1).map(_._2),
          amountColIndex = amountField
        )

  def apply[M](model: Model, lift: Msg => M): Html[M] = div()()
