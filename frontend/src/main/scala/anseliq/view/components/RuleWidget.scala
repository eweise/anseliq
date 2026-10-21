package anseliq.view.components

import com.raquo.laminar.api.L.*
import anseliq.view.ViewHelper
import anseliq.common.model.*

// Edits a spec's matching rule: a primary rule and any alternatives (an ORRule's rules, tried in
// order, each on what the earlier ones left unmatched; see InMemoryReconciler.alternatives). Each
// is edited with the same form, including each string field's transforms (e.g. normalize, so
// "INV-2026-0042" matches "inv20260042"). Alternatives can be added by hand or from rules learned
// from match suggestions. Anything the form has no controls for, such as a regex extract set
// through the API, is carried through to the saved rule rather than dropped.
object RuleWidget:

  case class LeafState(
    ruleType:    String                 = "StringMatch",
    inKey:       String                 = "",
    inConstant:  Boolean                = false,
    outKey:      String                 = "",
    outConstant: Boolean                = false,
    inModifier:  Option[StringModifier] = None,
    outModifier: Option[StringModifier] = None
  )

  // One rule: the primary, or one alternative.
  case class State(
    ruleType:    String                 = "NoOp",
    inKey:       String                 = "",
    inConstant:  Boolean                = false,
    outKey:      String                 = "",
    outConstant: Boolean                = false,
    andRules:    Seq[LeafState]         = Seq.empty,
    inModifier:  Option[StringModifier] = None,
    outModifier: Option[StringModifier] = None
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
          outConstant = outKey.isConstant,
          inModifier  = inKey.modifier,
          outModifier = outKey.modifier
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
            LeafState("StringMatch", ik.key, ik.isConstant, ok.key, ok.isConstant, ik.modifier, ok.modifier)
          case DateAttributeMatchRule(ik, ok) =>
            LeafState("DateMatch", ik.key, ik.isConstant, ok.key, ok.isConstant)
        }
        State(ruleType = "And", andRules = leaves)
      case _ => State()

    def toRule(s: State): Rule = s.ruleType match
      case "StringMatch" =>
        StringAttributeMatchRule(
          StringAttribute(s.inKey, s.inModifier, isConstant   = s.inConstant),
          StringAttribute(s.outKey, s.outModifier, isConstant = s.outConstant)
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
              StringAttribute(leaf.inKey, leaf.inModifier, isConstant   = leaf.inConstant),
              StringAttribute(leaf.outKey, leaf.outModifier, isConstant = leaf.outConstant)
            )
        })
      case _ => NoOpRule

  // Also used directly by MaintainMovementSpecPage's routing-filter field picker, which enumerates
  // one specific (just-selected) feed's fields rather than resolving a feed via a movement spec.
  def fieldsFor(feed: FileFeedPayload): Seq[String] =
    feed.dataSourceConfig.collect {
      case DataSourceConfig(CsvParserConfig(_, cols, _), _)        => cols.values.toSeq
      case DataSourceConfig(BaiParserConfig(fieldMappings), _)     => fieldMappings.values.toSeq
      case DataSourceConfig(JsonParserConfig(fieldMappings, _), _) => fieldMappings.values.toSeq
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
    accounts:          Signal[Seq[AccountPayload]],
    // Rules to add as alternatives, e.g. a learned rule the spec page offers.
    addAlternative: EventStream[Rule] = EventStream.empty
  ): (HtmlElement, Signal[Rule]) =
    val (primaryRule, alternativeRules) = initial match
      case ORRule(first :: rest) => (first, rest)
      case rule                  => (rule, Nil)
    val primary = Var(State.fromRule(primaryRule))
    // Each alternative's own state, with an id that keeps its form stable while others are removed.
    var nextId       = 0
    def newAlternative(state: State): (Int, Var[State]) =
      nextId += 1
      nextId -> Var(state)
    val alternatives = Var(alternativeRules.map(rule => newAlternative(State.fromRule(rule))))

    // Each side's field is labelled by its account (e.g. "Journal field") rather than In/Out Key.
    val inLabel = inMovementSpecId
      .combineWith(movementSpecs.signal, accounts)
      .map((id, specs, accts) => s"${ViewHelper.primarySideName(id, specs, accts)} field")
    val outLabel = outMovementSpecId
      .combineWith(movementSpecs.signal, accounts)
      .map((id, specs, accts) => s"${ViewHelper.secondarySideName(id, specs, accts)} field")

    val inCols = inMovementSpecId
      .combineWith(movementSpecs.signal)
      .combineWith(fileFeeds.signal)
      .map { case (id, specs, feeds) => columnsFor(id, specs, feeds) }
    val outCols = outMovementSpecId
      .combineWith(movementSpecs.signal)
      .combineWith(fileFeeds.signal)
      .map { case (id, specs, feeds) => columnsFor(id, specs, feeds) }

    def editor(idPrefix: String, state: Var[State], allowNoRule: Boolean) =
      ruleEditor(idPrefix, state, allowNoRule, inCols, outCols, inLabel, outLabel)

    val el = div(
      cls := "border-t border-md-outline pt-5 mt-2",
      addAlternative --> { rule =>
        val present = rule +: alternatives.now().map((_, state) => State.toRule(state.now()))
        if !present.contains(rule) then alternatives.update(_ :+ newAlternative(State.fromRule(rule)))
      },
      p(cls := "text-sm font-medium text-md-on-surface dark:text-gray-200 mb-4", "Matching Rule"),
      editor("", primary, allowNoRule = true),
      div(
        cls := "mt-5",
        p(cls := "text-sm font-medium text-md-on-surface dark:text-gray-200 mb-1", "Also matches on"),
        p(
          cls := "text-xs text-md-on-surface-variant dark:text-gray-400 mb-3",
          "Alternatives are tried in order after the rule above, each on whatever is still unmatched, so they never change what the rule above matches."
        ),
        children <-- alternatives.signal.split(_._1) { (id, initial, _) =>
          val state = initial._2
          div(
            cls := "border border-md-outline rounded p-3 mb-3",
            div(
              cls := "flex justify-between items-center mb-2",
              code(
                cls := "font-mono text-xs text-md-on-surface dark:text-gray-200",
                child.text <-- state.signal.map(s => describe(State.toRule(s)))
              ),
              button(
                cls := "text-md-error text-sm px-2",
                typ := "button",
                onClick --> (_ => alternatives.update(_.filterNot(_._1 == id))),
                "Remove"
              )
            ),
            editor(s"alt${id}_", state, allowNoRule = false)
          )
        },
        button(
          cls := "text-md-primary text-sm px-4 py-2 rounded-full border border-md-primary",
          typ := "button",
          onClick --> (_ => alternatives.update(_ :+ newAlternative(State(ruleType = "StringMatch")))),
          "+ Add alternative"
        )
      )
    )
    val rule = primary.signal
      .combineWith(alternatives.signal.flatMapSwitch(alts => Signal.combineSeq(alts.map(_._2.signal))))
      .map { (primaryState, alternativeStates) =>
        val primaryRule = State.toRule(primaryState)
        if alternativeStates.isEmpty then primaryRule else ORRule(primaryRule :: alternativeStates.map(State.toRule).toList)
      }
    (el, rule)

  // The form for one rule: its type, then its fields (and their transforms) or AND rows. An
  // alternative can't be "No Rule", since an alternative that matches nothing isn't runnable.
  private def ruleEditor(
    idPrefix:    String,
    state:       Var[State],
    allowNoRule: Boolean,
    inCols:      Signal[Seq[String]],
    outCols:     Signal[Seq[String]],
    inLabel:     Signal[String],
    outLabel:    Signal[String]
  ): HtmlElement =
    val ruleTypeOptions =
      (if allowNoRule then Seq("NoOp" -> "No Rule") else Nil) ++
        Seq("StringMatch" -> "String Match", "DateMatch" -> "Date Match", "And" -> "And")
    div(
      Form.selectField(
        s"${idPrefix}ruleType",
        "Rule Type",
        ruleTypeOptions,
        state.now().ruleType,
        v => state.update(_.copy(ruleType = v))
      ),
      // Narrowed to only react when ruleType itself changes (via .distinct) — rebuilding on every
      // state mutation would drop keystroke-by-keystroke focus on the constant-value and prefix
      // text inputs below, the same bug this app hit once already in MaintainUserPage.scala.
      child <-- state.signal.map(_.ruleType).distinct.map {
        case rt @ ("StringMatch" | "DateMatch") =>
          val inputType = if rt == "DateMatch" then "date" else "text"
          div(
            operandField(
              s"${idPrefix}in",
              inLabel,
              inCols,
              inputType,
              state,
              _.inKey,
              (s, v) => s.copy(inKey = v),
              _.inConstant,
              (s, v) => s.copy(inConstant = v),
              Option.when(rt == "StringMatch")((_.inModifier, (s, m) => s.copy(inModifier = m)))
            ),
            operandField(
              s"${idPrefix}out",
              outLabel,
              outCols,
              inputType,
              state,
              _.outKey,
              (s, v) => s.copy(outKey = v),
              _.outConstant,
              (s, v) => s.copy(outConstant = v),
              Option.when(rt == "StringMatch")((_.outModifier, (s, m) => s.copy(outModifier = m)))
            )
          )
        case "And" =>
          div(
            children <-- state.signal.map(_.andRules).splitByIndex { (index, _, leafSignal) =>
              leafRow(idPrefix, index, leafSignal, inCols, outCols, inLabel, outLabel, state)
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

  private def describe(rule: Rule): String = rule match
    case AndRule(rules) => rules.map(describe).mkString(" and ")
    case leaf           => RuleMatch(leaf, "").condition

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
    setConstant: (State, Boolean) => State,
    // A string field's transforms; None for a date field, which has none.
    transforms: Option[(State => Option[StringModifier], (State, Option[StringModifier]) => State)] = None
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
      ),
      transforms.map { (get, set) =>
        child <-- state.signal.map(getConstant).distinct.map(isConstant =>
          if isConstant then emptyNode else transformEditor(state, get, set)
        )
      }
    )

  // A field's transforms, applied in order before the two sides are compared. Offers the same steps
  // a learned rule can use (see StringModifier): ones whose SQL and Scala are checked to agree.
  private def transformEditor(
    state: Var[State],
    get:   State => Option[StringModifier],
    set:   (State, Option[StringModifier]) => State
  ): HtmlElement =
    def steps(s: State): List[StringModifier] = get(s) match
      case Some(Chain(steps)) => steps
      case Some(one)          => List(one)
      case None               => Nil
    def setSteps(s: State, steps: List[StringModifier]): State =
      set(
        s,
        steps match
          case Nil        => None
          case one :: Nil => Some(one)
          case many       => Some(Chain(many))
      )
    def add(step: StringModifier): Unit = state.update(s => setSteps(s, steps(s) :+ step))

    val askingForPrefix = Var(false)
    val prefix          = Var("")
    div(
      cls := "-mt-2 mb-4",
      div(
        cls := "flex flex-wrap items-center gap-2 text-xs text-md-on-surface-variant dark:text-gray-400",
        span("Transform:"),
        children <-- state.signal.map(steps).distinct.map(_.zipWithIndex.map { (step, i) =>
          span(
            cls := "inline-flex items-center gap-1 rounded-full bg-gray-100 dark:bg-gray-700 px-2 py-0.5 text-md-on-surface dark:text-gray-200",
            stepLabel(step),
            button(
              typ        := "button",
              cls        := "text-md-on-surface-variant dark:text-gray-400",
              aria.label := s"Remove ${stepLabel(step)}",
              onClick --> (_ => state.update(s => setSteps(s, steps(s).patch(i, Nil, 1)))),
              "✕"
            )
          )
        }),
        select(
          cls := "bg-transparent border border-md-outline rounded px-2 py-0.5",
          option(value := "", "+ Add transform"),
          option(value := "normalize", "Normalize (ignore case and punctuation)"),
          option(value := "trimLeadingZeros", "Trim leading zeros"),
          option(value := "stripPrefix", "Strip prefix…"),
          inContext(el =>
            onChange.mapToValue --> { choice =>
              choice match
                case "normalize"        => add(Normalize)
                case "trimLeadingZeros" => add(TrimLeadingZeros)
                case "stripPrefix"      => askingForPrefix.set(true)
                case _                  => ()
              el.ref.value = ""
            }
          )
        )
      ),
      child <-- askingForPrefix.signal.map(asking =>
        if !asking then emptyNode
        else
          div(
            cls := "flex items-center gap-2 mt-2",
            input(
              cls         := "border border-md-outline rounded px-2 py-1 text-sm bg-transparent text-md-on-surface dark:text-white",
              placeholder := "Prefix, e.g. ACH ",
              onMountFocus,
              controlled(value <-- prefix.signal, onInput.mapToValue --> prefix.writer)
            ),
            button(
              cls := "text-xs text-md-primary",
              typ := "button",
              onClick --> { _ =>
                if prefix.now().nonEmpty then add(StripPrefix(prefix.now()))
                prefix.set("")
                askingForPrefix.set(false)
              },
              "Add"
            ),
            button(
              cls := "text-xs text-md-on-surface-variant dark:text-gray-400",
              typ := "button",
              onClick --> { _ => prefix.set(""); askingForPrefix.set(false) },
              "Cancel"
            )
          )
      )
    )

  private def stepLabel(step: StringModifier): String = step match
    case Normalize            => "normalize"
    case TrimLeadingZeros     => "trim leading zeros"
    case StripPrefix(p)       => s"strip prefix \"$p\""
    case SubstringModifier(r) => s"extract /$r/"
    case Chain(steps)         => steps.map(stepLabel).mkString(", then ")

  private def leafRow(
    idPrefix:   String,
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
            s"${idPrefix}andType_$index",
            "Type",
            Seq("StringMatch" -> "String Match", "DateMatch" -> "Date Match"),
            ruleType,
            v => state.update(s => updateLeaf(s, _.copy(ruleType = v)))
          ),
          operandField(
            s"${idPrefix}andIn${index}_",
            inLabel,
            inCols,
            inputType,
            state,
            s => s.andRules.lift(index).map(_.inKey).getOrElse(""),
            (s, v) => updateLeaf(s, _.copy(inKey = v)),
            s => s.andRules.lift(index).exists(_.inConstant),
            (s, v) => updateLeaf(s, _.copy(inConstant = v)),
            Option.when(ruleType == "StringMatch")(
              (s => s.andRules.lift(index).flatMap(_.inModifier), (s, m) => updateLeaf(s, _.copy(inModifier = m)))
            )
          ),
          operandField(
            s"${idPrefix}andOut${index}_",
            outLabel,
            outCols,
            inputType,
            state,
            s => s.andRules.lift(index).map(_.outKey).getOrElse(""),
            (s, v) => updateLeaf(s, _.copy(outKey = v)),
            s => s.andRules.lift(index).exists(_.outConstant),
            (s, v) => updateLeaf(s, _.copy(outConstant = v)),
            Option.when(ruleType == "StringMatch")(
              (s => s.andRules.lift(index).flatMap(_.outModifier), (s, m) => updateLeaf(s, _.copy(outModifier = m)))
            )
          )
        )
      }
    )
