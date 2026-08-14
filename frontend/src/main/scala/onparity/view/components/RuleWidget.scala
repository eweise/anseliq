package onparity.view.components

import com.raquo.laminar.api.L.*
import onparity.common.model.*

object RuleWidget:

  case class LeafState(ruleType: String = "StringMatch", inKey: String = "", outKey: String = "")

  case class State(
    ruleType: String         = "NoOp",
    inKey:    String         = "",
    outKey:   String         = "",
    andRules: Seq[LeafState] = Seq.empty
  )

  object State:
    def fromRule(rule: Rule): State = rule match
      case NoOpRule =>
        State()
      case StringAttributeMatchRule(inKey, outKey) =>
        State(ruleType = "StringMatch", inKey = inKey.key, outKey = outKey.key)
      case DateAttributeMatchRule(inKey, outKey) =>
        State(ruleType = "DateMatch", inKey = inKey.key, outKey = outKey.key)
      case AndRule(rules) =>
        val leaves = rules.collect {
          case StringAttributeMatchRule(ik, ok) => LeafState("StringMatch", ik.key, ok.key)
          case DateAttributeMatchRule(ik, ok)   => LeafState("DateMatch", ik.key, ok.key)
        }
        State(ruleType = "And", andRules = leaves)
      case _ => State()

    def toRule(s: State): Rule = s.ruleType match
      case "StringMatch" =>
        StringAttributeMatchRule(StringAttribute(s.inKey), StringAttribute(s.outKey))
      case "DateMatch" =>
        DateAttributeMatchRule(DateAttribute(s.inKey), DateAttribute(s.outKey))
      case "And" =>
        AndRule(s.andRules.toList.map { leaf =>
          if leaf.ruleType == "DateMatch"
          then DateAttributeMatchRule(DateAttribute(leaf.inKey), DateAttribute(leaf.outKey))
          else StringAttributeMatchRule(StringAttribute(leaf.inKey), StringAttribute(leaf.outKey))
        })
      case _ => NoOpRule

  def columnsFor(specId: String, specs: Seq[MovementSpecPayload]): Seq[String] =
    specs
      .find(_.id.contains(specId))
      .flatMap(_.dataSourceConfig)
      .collect { case DataSourceConfig(CSVParserConfig(_, cols, _), _) => cols.values.toSeq }
      .getOrElse(Seq.empty)

  def apply(
    initial:           Rule,
    movementSpecs:     Var[Seq[MovementSpecPayload]],
    inMovementSpecId:  Signal[String],
    outMovementSpecId: Signal[String]
  ): (HtmlElement, Signal[Rule]) =
    val state = Var(State.fromRule(initial))

    val ruleTypeOptions =
      Seq("NoOp" -> "No Rule", "StringMatch" -> "String Match", "DateMatch" -> "Date Match", "And" -> "And")

    val inCols  = inMovementSpecId.combineWith(movementSpecs.signal).map((id, specs) => columnsFor(id, specs))
    val outCols = outMovementSpecId.combineWith(movementSpecs.signal).map((id, specs) => columnsFor(id, specs))

    val el = div(
      cls := "border-t border-md-outline pt-5 mt-2",
      p(cls := "text-sm font-medium text-md-on-surface dark:text-gray-200 mb-4", "Matching Rule"),
      Form.selectField(
        "ruleType",
        "Rule Type",
        ruleTypeOptions,
        state.now().ruleType,
        v => state.update(_.copy(ruleType = v))
      ),
      child <-- state.signal.map { s =>
        s.ruleType match
          case "StringMatch" | "DateMatch" =>
            div(
              child <-- inCols.map(cols =>
                Form
                  .selectField("inKey", "In Key", cols.map(c => c -> c), s.inKey, v => state.update(_.copy(inKey = v)))
              ),
              child <-- outCols.map(cols =>
                Form.selectField(
                  "outKey",
                  "Out Key",
                  cols.map(c => c -> c),
                  s.outKey,
                  v => state.update(_.copy(outKey = v))
                )
              )
            )
          case "And" =>
            div(
              children <-- state.signal.map(_.andRules.zipWithIndex.map { case (leaf, i) =>
                leafRow(i, leaf, inCols, outCols, state)
              }),
              button(
                cls := "text-md-primary text-sm px-4 py-2 rounded-full border border-md-primary mt-1",
                typ := "button",
                onClick --> (_ => state.update(s => s.copy(andRules = s.andRules :+ LeafState()))),
                "+ Add Rule"
              )
            )
          case _ => div()
      }
    )
    (el, state.signal.map(State.toRule))

  private def leafRow(
    index:   Int,
    leaf:    LeafState,
    inCols:  Signal[Seq[String]],
    outCols: Signal[Seq[String]],
    state:   Var[State]
  ): HtmlElement =
    div(
      cls := "border border-md-outline rounded p-3 mb-3",
      div(
        cls := "flex justify-between items-center mb-2",
        p(cls := "text-sm font-medium text-md-on-surface dark:text-gray-200", s"Rule ${index + 1}"),
        button(
          cls := "text-md-error text-sm px-2",
          typ := "button",
          onClick --> (_ => state.update(s => s.copy(andRules = s.andRules.patch(index, Nil, 1)))),
          "Remove"
        )
      ),
      Form.selectField(
        s"andType_$index",
        "Type",
        Seq("StringMatch" -> "String Match", "DateMatch" -> "Date Match"),
        leaf.ruleType,
        v => state.update(s => s.copy(andRules = s.andRules.updated(index, s.andRules(index).copy(ruleType = v))))
      ),
      child <-- inCols.map(cols =>
        Form.selectField(
          s"andInKey_$index",
          "In Key",
          cols.map(c => c -> c),
          leaf.inKey,
          v => state.update(s => s.copy(andRules = s.andRules.updated(index, s.andRules(index).copy(inKey = v))))
        )
      ),
      child <-- outCols.map(cols =>
        Form.selectField(
          s"andOutKey_$index",
          "Out Key",
          cols.map(c => c -> c),
          leaf.outKey,
          v => state.update(s => s.copy(andRules = s.andRules.updated(index, s.andRules(index).copy(outKey = v))))
        )
      )
    )
