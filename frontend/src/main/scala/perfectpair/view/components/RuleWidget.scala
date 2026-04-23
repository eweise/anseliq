package perfectpair.view.components

import perfectpair.common.model.*
import tyrian.EmptyAttribute
import tyrian.Html.*
import tyrian.Html

object RuleWidget:

  case class LeafModel(
    ruleType: String = "StringMatch",
    inKey:    String = "",
    outKey:   String = ""
  )

  case class Model(
    ruleType: String         = "NoOp",
    inKey:    String         = "",
    outKey:   String         = "",
    andRules: Seq[LeafModel] = Seq()
  )

  enum Msg:
    case SelectRuleType(ruleType: String)
    case UpdateInKey(key: String)
    case UpdateOutKey(key: String)
    case AddAndRule
    case RemoveAndRule(index: Int)
    case UpdateAndRuleType(index: Int, ruleType: String)
    case UpdateAndRuleInKey(index: Int, key: String)
    case UpdateAndRuleOutKey(index: Int, key: String)

  def update(model: Model, msg: Msg): Model =
    msg match
      case Msg.SelectRuleType(rt)          => model.copy(ruleType = rt)
      case Msg.UpdateInKey(key)            => model.copy(inKey = key)
      case Msg.UpdateOutKey(key)           => model.copy(outKey = key)
      case Msg.AddAndRule                  => model.copy(andRules = model.andRules :+ LeafModel())
      case Msg.RemoveAndRule(i)            => model.copy(andRules = model.andRules.patch(i, Nil, 1))
      case Msg.UpdateAndRuleType(i, rt)    => model.copy(andRules = model.andRules.updated(i, model.andRules(i).copy(ruleType = rt)))
      case Msg.UpdateAndRuleInKey(i, key)  => model.copy(andRules = model.andRules.updated(i, model.andRules(i).copy(inKey = key)))
      case Msg.UpdateAndRuleOutKey(i, key) => model.copy(andRules = model.andRules.updated(i, model.andRules(i).copy(outKey = key)))

  def fromRule(rule: Rule): Model =
    rule match
      case NoOpRule =>
        Model()
      case StringAttributeMatchRule(inKey, outKey) =>
        Model(ruleType = "StringMatch", inKey = inKey.key, outKey = outKey.key)
      case DateAttributeMatchRule(inKey, outKey) =>
        Model(ruleType = "DateMatch", inKey = inKey.key, outKey = outKey.key)
      case AndRule(rules) =>
        val leaves = rules.collect {
          case StringAttributeMatchRule(inKey, outKey) => LeafModel("StringMatch", inKey.key, outKey.key)
          case DateAttributeMatchRule(inKey, outKey)   => LeafModel("DateMatch",   inKey.key, outKey.key)
        }
        Model(ruleType = "And", andRules = leaves)
      case _ => Model()

  def toRule(model: Model): Rule =
    model.ruleType match
      case "StringMatch" =>
        StringAttributeMatchRule(StringAttribute(model.inKey), StringAttribute(model.outKey))
      case "DateMatch" =>
        DateAttributeMatchRule(DateAttribute(model.inKey), DateAttribute(model.outKey))
      case "And" =>
        AndRule(model.andRules.toList.map { leaf =>
          if leaf.ruleType == "DateMatch" then
            DateAttributeMatchRule(DateAttribute(leaf.inKey), DateAttribute(leaf.outKey))
          else
            StringAttributeMatchRule(StringAttribute(leaf.inKey), StringAttribute(leaf.outKey))
        })
      case _ => NoOpRule

  def columnsFor(specId: String, specs: Seq[MovementSpecPayload]): Seq[String] =
    specs
      .find(_.id.contains(specId))
      .flatMap(_.dataSourceConfig)
      .collect { case DataSourceConfig(CSVParserConfig(_, cols, _)) => cols.values.toSeq }
      .getOrElse(Seq.empty)

  private val ruleTypeOptions: Seq[(String, String)] = Seq(
    "NoOp"        -> "No Rule",
    "StringMatch" -> "String Match",
    "DateMatch"   -> "Date Match",
    "And"         -> "And"
  )

  private val leafRuleTypeOptions: Seq[(String, String)] = Seq(
    "StringMatch" -> "String Match",
    "DateMatch"   -> "Date Match"
  )

  private def selectField[M](
    fieldID:   String,
    labelText: String,
    selected:  String,
    options:   Seq[(String, String)],
    onSelect:  String => M
  ): Html[M] =
    div(cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded")(
      label(
        cls := "absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide text-md-on-surface-variant",
        attribute("for", fieldID)
      )(text(labelText)),
      select(
        id  := fieldID,
        cls := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-base focus:outline-none rounded",
        onChange(onSelect)
      )(
        options.map { case (v, lbl) =>
          option(
            value := v,
            if v == selected then attribute("selected", "selected") else EmptyAttribute
          )(text(lbl))
        }*
      )
    )

  private def leafRow[M](
    index:   Int,
    leaf:    LeafModel,
    inCols:  Seq[String],
    outCols: Seq[String],
    lift:    Msg => M
  ): Html[M] =
    div(cls := "border border-md-outline rounded p-3 mb-3")(
      div(cls := "flex justify-between items-center mb-2")(
        p(cls := "text-sm font-medium text-md-on-surface")(text(s"Rule ${index + 1}")),
        button(
          cls     := "text-md-error text-sm px-2",
          tpe     := "button",
          onClick(lift(Msg.RemoveAndRule(index)))
        )(text("Remove"))
      ),
      selectField(s"andType_$index",   "Type",    leaf.ruleType, leafRuleTypeOptions,     v => lift(Msg.UpdateAndRuleType(index, v))),
      selectField(s"andInKey_$index",  "In Key",  leaf.inKey,    inCols.map(c => c -> c), v => lift(Msg.UpdateAndRuleInKey(index, v))),
      selectField(s"andOutKey_$index", "Out Key", leaf.outKey,   outCols.map(c => c -> c), v => lift(Msg.UpdateAndRuleOutKey(index, v)))
    )

  def apply[M](
    model:             Model,
    movementSpecs:     Seq[MovementSpecPayload],
    inMovementSpecId:  String,
    outMovementSpecId: String,
    lift:              Msg => M
  ): Html[M] =
    val inCols  = columnsFor(inMovementSpecId, movementSpecs)
    val outCols = columnsFor(outMovementSpecId, movementSpecs)
    div(cls := "border-t border-md-outline pt-5 mt-2")(
      p(cls := "text-sm font-medium text-md-on-surface mb-4")(text("Matching Rule")),
      selectField("ruleType", "Rule Type", model.ruleType, ruleTypeOptions, v => lift(Msg.SelectRuleType(v))),
      model.ruleType match
        case "StringMatch" | "DateMatch" =>
          div()(
            selectField("inKey",  "In Key",  model.inKey,  inCols.map(c => c -> c),  v => lift(Msg.UpdateInKey(v))),
            selectField("outKey", "Out Key", model.outKey, outCols.map(c => c -> c), v => lift(Msg.UpdateOutKey(v)))
          )
        case "And" =>
          div()(
            div()(model.andRules.zipWithIndex.map { case (leaf, i) =>
              leafRow(i, leaf, inCols, outCols, lift)
            }*),
            button(
              cls     := "text-md-primary text-sm px-4 py-2 rounded-full border border-md-primary mt-1",
              tpe     := "button",
              onClick(lift(Msg.AddAndRule))
            )(text("+ Add Rule"))
          )
        case _ => div()()
    )
