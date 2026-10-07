package anseliq.view.components

import com.raquo.laminar.api.L.*
import anseliq.view.ViewHelper
import anseliq.common.model.*

object RuleWidget:

  case class LeafState(
    ruleType:    String  = "StringMatch",
    inKey:       String  = "",
    inConstant:  Boolean = false,
    outKey:      String  = "",
    outConstant: Boolean = false
  )

  case class State(
    ruleType:    String         = "NoOp",
    inKey:       String         = "",
    inConstant:  Boolean        = false,
    outKey:      String         = "",
    outConstant: Boolean        = false,
    andRules:    Seq[LeafState] = Seq.empty
  )

  object State:
    def fromRule(rule: Rule): State = rule match
      case NoOpRule =>
        State()
      case StringAttributeMatchRule(inKey, outKey) =>
        State(
          ruleType    = "StringMatch",
          inKey       = inKey.key,
          inConstant  = inKey.isConstant,
          outKey      = outKey.key,
          outConstant = outKey.isConstant
        )
      case DateAttributeMatchRule(inKey, outKey) =>
        State(
          ruleType    = "DateMatch",
          inKey       = inKey.key,
          inConstant  = inKey.isConstant,
          outKey      = outKey.key,
          outConstant = outKey.isConstant
        )
      case AndRule(rules) =>
        val leaves = rules.collect {
          case StringAttributeMatchRule(ik, ok) =>
            LeafState("StringMatch", ik.key, ik.isConstant, ok.key, ok.isConstant)
          case DateAttributeMatchRule(ik, ok) =>
            LeafState("DateMatch", ik.key, ik.isConstant, ok.key, ok.isConstant)
        }
        State(ruleType = "And", andRules = leaves)
      case _ => State()

    def toRule(s: State): Rule = s.ruleType match
      case "StringMatch" =>
        StringAttributeMatchRule(
          StringAttribute(s.inKey, isConstant  = s.inConstant),
          StringAttribute(s.outKey, isConstant = s.outConstant)
        )
      case "DateMatch" =>
        DateAttributeMatchRule(
          DateAttribute(s.inKey, isConstant  = s.inConstant),
          DateAttribute(s.outKey, isConstant = s.outConstant)
        )
      case "And" =>
        AndRule(s.andRules.toList.map { leaf =>
          if leaf.ruleType == "DateMatch"
          then
            DateAttributeMatchRule(
              DateAttribute(leaf.inKey, isConstant  = leaf.inConstant),
              DateAttribute(leaf.outKey, isConstant = leaf.outConstant)
            )
          else
            StringAttributeMatchRule(
              StringAttribute(leaf.inKey, isConstant  = leaf.inConstant),
              StringAttribute(leaf.outKey, isConstant = leaf.outConstant)
            )
        })
      case _ => NoOpRule

  // Also used directly by MaintainMovementSpecPage's routing-filter field picker, which enumerates
  // one specific (just-selected) feed's fields rather than resolving a feed via a movement spec.
  def fieldsFor(feed: FileFeedPayload): Seq[String] =
    feed.dataSourceConfig.collect {
      case DataSourceConfig(CSVParserConfig(_, cols, _), _)        => cols.values.toSeq
      case DataSourceConfig(BAIParserConfig(fieldMappings), _)     => fieldMappings.values.toSeq
      case DataSourceConfig(JSONParserConfig(fieldMappings, _), _) => fieldMappings.values.toSeq
      case DataSourceConfig(FixedWidthParserConfig(_, fields), _)  => fields.map(_.name)
    }
      .getOrElse(Seq.empty)

  def columnsFor(specId: String, specs: Seq[MovementSpecPayload], fileFeeds: Seq[FileFeedPayload]): Seq[String] =
    specs
      .find(_.id.contains(specId))
      .flatMap(spec => fileFeeds.find(_.id.contains(spec.fileFeedId)))
      .map(fieldsFor)
      .getOrElse(Seq.empty)

  def apply(
    initial:           Rule,
    movementSpecs:     Var[Seq[MovementSpecPayload]],
    fileFeeds:         Var[Seq[FileFeedPayload]],
    inMovementSpecId:  Signal[String],
    outMovementSpecId: Signal[String],
    accounts:          Signal[Seq[AccountPayload]]
  ): (HtmlElement, Signal[Rule]) =
    val state = Var(State.fromRule(initial))

    // Each side's field is labelled by its account (e.g. "Journal field") rather than In/Out Key.
    val inLabel = inMovementSpecId
      .combineWith(movementSpecs.signal, accounts)
      .map((id, specs, accts) => s"${ViewHelper.primarySideName(id, specs, accts)} field")
    val outLabel = outMovementSpecId
      .combineWith(movementSpecs.signal, accounts)
      .map((id, specs, accts) => s"${ViewHelper.secondarySideName(id, specs, accts)} field")

    val ruleTypeOptions =
      Seq("NoOp" -> "No Rule", "StringMatch" -> "String Match", "DateMatch" -> "Date Match", "And" -> "And")

    val inCols = inMovementSpecId
      .combineWith(movementSpecs.signal)
      .combineWith(fileFeeds.signal)
      .map { case (id, specs, feeds) => columnsFor(id, specs, feeds) }
    val outCols = outMovementSpecId
      .combineWith(movementSpecs.signal)
      .combineWith(fileFeeds.signal)
      .map { case (id, specs, feeds) => columnsFor(id, specs, feeds) }

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
      // Narrowed to only react when ruleType itself changes (via .distinct) — rebuilding on every
      // state mutation (the previous behavior) is harmless for dropdowns (today's only in/out
      // controls) but would drop keystroke-by-keystroke focus on the new constant-value text
      // inputs below, the same bug this app hit once already in MaintainUserPage.scala.
      child <-- state.signal.map(_.ruleType).distinct.map {
        case rt @ ("StringMatch" | "DateMatch") =>
          val inputType = if rt == "DateMatch" then "date" else "text"
          div(
            operandField(
              "in",
              inLabel,
              inCols,
              inputType,
              state,
              _.inKey,
              (s, v) => s.copy(inKey = v),
              _.inConstant,
              (s, v) => s.copy(inConstant = v)
            ),
            operandField(
              "out",
              outLabel,
              outCols,
              inputType,
              state,
              _.outKey,
              (s, v) => s.copy(outKey = v),
              _.outConstant,
              (s, v) => s.copy(outConstant = v)
            )
          )
        case "And" =>
          div(
            children <-- state.signal.map(_.andRules).splitByIndex { (index, _, leafSignal) =>
              leafRow(index, leafSignal, inCols, outCols, inLabel, outLabel, state)
            },
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

  // One side (the Primary or Secondary account's field) of a String/Date Match rule: toggles between a field-name
  // dropdown (today's only option) and a typed constant value, via a checkbox. Reacts only to the
  // isConstant flag (via .distinct) rather than the whole State, so typing into the constant
  // textbox — which updates State on every keystroke via onKey — doesn't rebuild this element and
  // drop focus out from under itself. get/set are threaded through as plain functions rather than
  // hand-writing this twice (top-level in/out and, below, each AndRule leaf's in/out) since the
  // only difference between call sites is which State field they read/write.
  private def operandField(
    idPrefix:    String,
    fieldLabel:  Signal[String],
    cols:        Signal[Seq[String]],
    inputType:   String,
    state:       Var[State],
    getKey:      State => String,
    setKey:      (State, String) => State,
    getConstant: State => Boolean,
    setConstant: (State, Boolean) => State
  ): HtmlElement =
    div(
      cls := "mb-1",
      child <-- state.signal.map(getConstant).distinct.map { isConstant =>
        if isConstant then
          // Rebuilt only when the label changes (picking a different movement spec), not on each
          // keystroke, so typing keeps focus.
          div(
            child <-- fieldLabel.distinct.map(label =>
              Form.field(
                s"${idPrefix}Value",
                s"$label (constant)",
                getKey(state.now()),
                v => state.update(s => setKey(s, v)),
                inputType = inputType
              )
            )
          )
        else
          div(
            child <-- cols.combineWith(fieldLabel).map((cs, label) =>
              Form.selectField(
                s"${idPrefix}Key",
                label,
                cs.map(c => c -> c),
                getKey(state.now()),
                v => state.update(s => setKey(s, v))
              )
            )
          )
      },
      label(
        cls := "flex items-center gap-2 mt-1 mb-4 text-xs text-md-on-surface-variant dark:text-gray-400 cursor-pointer",
        input(
          typ := "checkbox",
          checked <-- state.signal.map(getConstant),
          onClick --> (_ => state.update(s => setConstant(s, !getConstant(s))))
        ),
        "Match a constant value instead of a field"
      )
    )

  private def leafRow(
    index:      Int,
    leafSignal: Signal[LeafState],
    inCols:     Signal[Seq[String]],
    outCols:    Signal[Seq[String]],
    inLabel:    Signal[String],
    outLabel:   Signal[String],
    state:      Var[State]
  ): HtmlElement =
    // Defensive against a stale update landing after this leaf's index has shifted or the row was
    // removed (e.g. a keystroke event queued right as "Remove" is clicked) — a no-op rather than
    // an out-of-bounds exception.
    def updateLeaf(s: State, f: LeafState => LeafState): State =
      if s.andRules.isDefinedAt(index) then s.copy(andRules = s.andRules.updated(index, f(s.andRules(index)))) else s

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
      // Narrowed to only react when this leaf's own ruleType changes, same reasoning as the
      // top-level rule-type switch above.
      child <-- leafSignal.map(_.ruleType).distinct.map { ruleType =>
        val inputType = if ruleType == "DateMatch" then "date" else "text"
        div(
          Form.selectField(
            s"andType_$index",
            "Type",
            Seq("StringMatch" -> "String Match", "DateMatch" -> "Date Match"),
            ruleType,
            v => state.update(s => updateLeaf(s, _.copy(ruleType = v)))
          ),
          operandField(
            s"andIn${index}_",
            inLabel,
            inCols,
            inputType,
            state,
            s => s.andRules.lift(index).map(_.inKey).getOrElse(""),
            (s, v) => updateLeaf(s, _.copy(inKey = v)),
            s => s.andRules.lift(index).exists(_.inConstant),
            (s, v) => updateLeaf(s, _.copy(inConstant = v))
          ),
          operandField(
            s"andOut${index}_",
            outLabel,
            outCols,
            inputType,
            state,
            s => s.andRules.lift(index).map(_.outKey).getOrElse(""),
            (s, v) => updateLeaf(s, _.copy(outKey = v)),
            s => s.andRules.lift(index).exists(_.outConstant),
            (s, v) => updateLeaf(s, _.copy(outConstant = v))
          )
        )
      }
    )
