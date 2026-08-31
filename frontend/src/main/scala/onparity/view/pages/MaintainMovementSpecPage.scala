package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{AccountPayload, FileFeedPayload, MovementSpecPayload, RoutingFilter}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.{AppState, SessionStore}
import onparity.view.components.{Buttons, Form, RuleWidget}
import upickle.default.*

object MaintainMovementSpecPage:

  private enum LoadState:
    case Loading
    case Ready(spec: MovementSpecPayload)

  def apply(outAccountId: String, inAccountId: String, id: String): HtmlElement =
    AppState.activeFundFlow.now() match
      case None     => div(onMountCallback(_ => Nav.toFundFlows()))
      case Some(ff) => renderPage(outAccountId, inAccountId, id, ff)

  private def renderPage(
    outAccountId: String,
    inAccountId:  String,
    id:           String,
    ff:           onparity.common.model.FundFlowPayload
  ): HtmlElement =
    val ffId      = ff.id.getOrElse("")
    val blank     = MovementSpecPayload(outAccountId = outAccountId, inAccountId = inAccountId)
    val loadState = Var[LoadState](if id == "new" then LoadState.Ready(blank) else LoadState.Loading)
    val loadBus   = EventBus[Unit]()

    div(
      loadBus.events
        .flatMapSwitch(_ =>
          ApiClient.get[Seq[MovementSpecPayload]](
            HttpPath.url(s"${HttpPath.fundFlows}/$ffId/movement-specs"),
            SessionStore.token
          )
        )
        .apiBind(specs => loadState.set(LoadState.Ready(specs.find(_.id.contains(id)).getOrElse(blank)))),
      onMountCallback(_ => if id != "new" then loadBus.emit(())),
      child <-- loadState.signal.map {
        case LoadState.Loading =>
          div(
            cls := "min-h-screen bg-md-surface-variant dark:bg-gray-900 flex items-center justify-center pt-20 px-4",
            span(cls := "text-md-on-surface-variant", "Loading...")
          )
        case LoadState.Ready(initial) => renderForm(ffId, initial)
      }
    )

  private def renderForm(ffId: String, initial: MovementSpecPayload): HtmlElement =
    val spec      = Var(initial)
    val accounts  = Var(Seq.empty[AccountPayload])
    val fileFeeds = Var(Seq.empty[FileFeedPayload])
    val saveBus   = EventBus[Unit]()

    div(
      ApiClient
        .get[Seq[AccountPayload]](HttpPath.url(s"${HttpPath.fundFlows}/$ffId/accounts"), SessionStore.token)
        .apiBind(accs => accounts.set(accs)),
      ApiClient
        .get[Seq[FileFeedPayload]](HttpPath.url(HttpPath.fileFeeds), SessionStore.token)
        .apiBind(feeds => fileFeeds.set(feeds)),
      saveBus.events.flatMapSwitch { _ =>
        val s     = spec.now()
        val token = SessionStore.token
        if s.id.isDefined
        then
          ApiClient.put[MovementSpecPayload, MovementSpecPayload](
            HttpPath.url(s"${HttpPath.movementSpec}/${s.id.get}"),
            s,
            token
          )
        else ApiClient.post[MovementSpecPayload, MovementSpecPayload](HttpPath.url(HttpPath.movementSpec), s, token)
      }
        .apiBind(_ => Nav.back()),
      cls := "min-h-screen bg-md-surface-variant dark:bg-gray-900 flex items-start justify-center pt-20 px-4",
      div(
        cls := "bg-white dark:bg-gray-800 rounded-xl shadow-md w-full max-w-md p-8",
        h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-7", "Movement Spec"),
        Form.field("name", "Name", initial.name, v => spec.update(_.copy(name = v))),
        child <-- accounts.signal.map { accs =>
          val selectableAccs =
            if initial.id.isEmpty && initial.inAccountId.nonEmpty && initial.outAccountId.nonEmpty then
              accs.filter(a => a.id.contains(initial.inAccountId) || a.id.contains(initial.outAccountId))
            else accs
          div(
            accountSelect(
              "inAccountId",
              "In Account",
              initial.inAccountId,
              selectableAccs,
              v => spec.update(_.copy(inAccountId = v))
            ),
            accountSelect(
              "outAccountId",
              "Out Account",
              initial.outAccountId,
              selectableAccs,
              v => spec.update(_.copy(outAccountId = v))
            )
          )
        },
        div(
          cls := "mt-4",
          div(
            cls := "flex items-center justify-between mb-2",
            span(cls := Form.sectionLabelCls, "File Feed"),
            Buttons.textButton("+ New Feed", Observer[Unit](_ => Nav.toMaintainFileFeed()))
          ),
          child <-- fileFeeds.signal.map { feeds =>
            fileFeedSelect(initial.fileFeedId, feeds, v => spec.update(_.copy(fileFeedId = v)))
          }
        ),
        // Routing filter: only meaningful once a feed is chosen — enumerates the same feed-parsed
        // fields RuleWidget already offers for matching rules, since a routing filter picks one of
        // them too. Narrowed to fileFeedId (outer) and routingFilter's field name (inner) via
        // .distinct so typing the match value doesn't rebuild either block and drop input focus —
        // same reactive-narrowing pattern as RuleWidget.operandField.
        child <-- spec.signal
          .map(_.fileFeedId)
          .distinct
          .combineWith(fileFeeds.signal)
          .map { (feedId, feeds) =>
            val fields = feeds.find(_.id.contains(feedId)).map(RuleWidget.fieldsFor).getOrElse(Seq.empty)
            if feedId.isEmpty || fields.isEmpty then div()
            else
              div(
                cls := "mt-4",
                span(cls := Form.sectionLabelCls, "Routing Filter"),
                div(
                  cls := s"mt-1 mb-2 text-xs ${Form.mutedTextCls}",
                  "Only rows matching this field/value are recorded against this account pair — leave " +
                    "unset if this feed only covers one account."
                ),
                Form.selectField(
                  "routingField",
                  "Field",
                  ("" -> "(none — claims every row from this feed)") +: fields.map(f => f -> f),
                  spec.now().routingFilter.map(_.propertyField).getOrElse(""),
                  v =>
                    spec.update(s =>
                      s.copy(routingFilter =
                        if v.isEmpty then None
                        else Some(s.routingFilter.map(_.copy(propertyField = v)).getOrElse(RoutingFilter(v, "")))
                      )
                    )
                ),
                child <-- spec.signal.map(_.routingFilter.map(_.propertyField)).distinct.map {
                  case None | Some("") => div()
                  case Some(_) =>
                    Form.field(
                      "routingValue",
                      "Equals",
                      spec.now().routingFilter.map(_.matchValue).getOrElse(""),
                      v => spec.update(s => s.copy(routingFilter = s.routingFilter.map(_.copy(matchValue = v))))
                    )
                }
              )
          },
        div(
          cls := "flex justify-end gap-2 mt-6",
          Buttons.textButton("Cancel", Observer[Unit](_ => Nav.back())),
          Buttons.linkButton("Save", Observer[Unit](_ => saveBus.emit(())))
        )
      )
    )

  private def accountSelect(
    id:       String,
    lbl:      String,
    selected: String,
    accounts: Seq[AccountPayload],
    changeFn: String => Unit
  ): HtmlElement =
    div(
      cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded",
      label(
        forId := id,
        cls   := "absolute -top-2.5 left-3 bg-white dark:bg-gray-800 px-1 text-xs font-medium tracking-wide text-md-on-surface-variant dark:text-gray-400",
        lbl
      ),
      select(
        idAttr := id,
        cls    := "w-full px-4 py-3.5 bg-transparent text-md-on-surface dark:text-white text-base focus:outline-none rounded",
        onMountCallback(ctx => ctx.thisNode.ref.value = selected),
        onChange.mapToValue --> Observer[String](changeFn)
      ).amend(accounts.map(a => option(value := a.id.getOrElse(""), a.name))*)
    )

  private def fileFeedSelect(
    selected: String,
    feeds:    Seq[FileFeedPayload],
    changeFn: String => Unit
  ): HtmlElement =
    Form.selectField(
      "fileFeedId",
      "File Feed",
      feeds.map(f => f.id.getOrElse("") -> (if f.name.nonEmpty then f.name else "(unnamed feed)")),
      selected,
      changeFn
    )
