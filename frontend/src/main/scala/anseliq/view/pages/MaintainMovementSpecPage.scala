package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{AccountPayload, AccountTypePayload, FileFeedPayload, MovementSpecPayload, RoutingFilter}
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.{AppState, SessionStore}
import anseliq.view.ViewHelper
import anseliq.view.components.{Buttons, Form, RuleWidget}
import upickle.default.*

object MaintainMovementSpecPage:

  // Rendered inside Modal by LedgerPage (its only caller) — onClose defaults to Nav.back() so
  // this still works if ever reached directly via its route
  // (/components/maintain-movement-spec/:outAccountId/:inAccountId/:id).
  def apply(
    outAccountId: String,
    inAccountId:  String,
    id:           String,
    onClose:      () => Unit = () => Nav.back()
  ): HtmlElement =
    AppState.activeLedger.now() match
      case None     => div(onMountCallback(_ => Nav.toLedgers()))
      case Some(ff) => renderPage(outAccountId, inAccountId, id, ff, onClose)

  private def renderPage(
    outAccountId: String,
    inAccountId:  String,
    id:           String,
    ff:           anseliq.common.model.LedgerPayload,
    onClose:      () => Unit
  ): HtmlElement =
    val ffId      = ff.id.getOrElse("")
    val blank     = MovementSpecPayload(outAccountId = outAccountId, inAccountId = inAccountId)
    val loadState = Var[LoadState](if id == "new" then LoadState.Ready(blank) else LoadState.Loading)

    div(
      // Fires directly within the fetch pipeline rather than via onMountCallback + EventBus.emit —
      // EventBus has no replay buffer, so an emit before flatMapSwitch has actually subscribed is
      // silently dropped. See MaintainAccountPage's identical fix for the full explanation; this
      // page has the same shape and the same latent bug once rendered inside a Modal.
      (if id != "new" then EventStream.fromValue(()) else EventStream.empty)
        .flatMapSwitch(_ =>
          ApiClient.get[Seq[MovementSpecPayload]](
            HttpPath.url(s"${HttpPath.ledgers}/$ffId/movement-specs"),
            SessionStore.token
          )
        )
        .apiBind(specs => loadState.set(LoadState.Ready(specs.find(_.id.contains(id)).getOrElse(blank)))),
      child <-- loadState.signal.map {
        case LoadState.Loading        => div(cls := "text-md-on-surface-variant", "Loading...")
        case LoadState.Ready(initial) => renderForm(ffId, initial, onClose)
      }
    )

  private def renderForm(ffId: String, initial: MovementSpecPayload, onClose: () => Unit): HtmlElement =
    val spec      = Var(initial)
    val accounts  = Var(Seq.empty[AccountPayload])
    val fileFeeds = Var(Seq.empty[FileFeedPayload])
    val saveBus   = EventBus[Unit]()
    val saveError = Var[Option[String]](None)

    // The "Account" and "Recon Account" fields are tracked independently of spec.inAccountId/
    // outAccountId, because which raw field each one belongs to depends on the Account's own type
    // (see ViewHelper.accountLinkFields) — a Primary account is outAccountId, a Secondary is
    // inAccountId, and Recon is whichever field the other one isn't. Seeded from `initial` here and
    // corrected once account types are known (below), since a spec loaded for editing might have
    // its Recon side stored in either field depending on whether it's Primary- or Secondary-paired.
    val accountId      = Var(initial.inAccountId)
    val reconAccountId = Var(initial.outAccountId)

    div(
      ApiClient
        .get[Seq[AccountPayload]](HttpPath.url(s"${HttpPath.ledgers}/$ffId/accounts"), SessionStore.token)
        .apiBind { accs =>
          val initialInIsRecon = accs.find(_.id.contains(initial.inAccountId)).exists(_.accountType == AccountTypePayload.Recon)
          if initialInIsRecon then
            accountId.set(initial.outAccountId)
            reconAccountId.set(initial.inAccountId)
          // Set last: the accounts-dependent render below reads accountId/reconAccountId via
          // .now(), so they must already hold the corrected values by the time this triggers it.
          accounts.set(accs)
        },
      ApiClient
        .get[Seq[FileFeedPayload]](HttpPath.url(HttpPath.fileFeeds), SessionStore.token)
        .apiBind(feeds => fileFeeds.set(feeds)),
      saveBus.events.flatMapSwitch { _ =>
        val s             = spec.now()
        val chosenAccount = accounts.now().find(_.id.contains(accountId.now()))
        val reconAccount  = accounts.now().find(_.id.contains(reconAccountId.now()))
        (chosenAccount, reconAccount) match
          case _ if s.fileFeedId.isEmpty =>
            saveError.set(Some("Please select a File Feed"))
            EventStream.empty
          case (Some(acc), Some(recon)) =>
            saveError.set(None)
            val (outId, inId) = ViewHelper.accountLinkFields(acc, recon)
            val finalSpec     = s.copy(outAccountId = outId, inAccountId = inId)
            val token         = SessionStore.token
            if finalSpec.id.isDefined
            then
              ApiClient.put[MovementSpecPayload, MovementSpecPayload](
                HttpPath.url(s"${HttpPath.movementSpec}/${finalSpec.id.get}"),
                finalSpec,
                token
              )
            else
              ApiClient
                .post[MovementSpecPayload, MovementSpecPayload](HttpPath.url(HttpPath.movementSpec), finalSpec, token)
          case _ =>
            saveError.set(Some("Please select an Account and a Recon Account"))
            EventStream.empty
      }
        .apiBind(_ => onClose()),
      h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-7", "Movement Spec"),
      Form.field("name", "Name", initial.name, v => spec.update(_.copy(name = v))),
      // A movement spec always links exactly one Recon account with one Primary-or-Secondary
      // account. Filtering each dropdown to type-compatible accounts (rather than listing every
      // account in the ledger) means an invalid combination can't even be selected here — the
      // backend still enforces the same rule (LedgerOperations.validateAccountLink) since this
      // form isn't the only way a spec gets created/updated. Which raw field (in/out) each side
      // ends up in is computed at save time from the chosen Account's type — see
      // ViewHelper.accountLinkFields — not fixed to either dropdown.
      child <-- accounts.signal.map { accs =>
        val accountOptions = accs.filter(_.accountType != AccountTypePayload.Recon)
        val reconOptions   = accs.filter(_.accountType == AccountTypePayload.Recon)
        div(
          accountSelect(
            "account",
            "Account",
            accountId.now(),
            accountOptions,
            v => accountId.set(v)
          ),
          accountSelect(
            "reconAccount",
            "Recon Account",
            reconAccountId.now(),
            reconOptions,
            v => reconAccountId.set(v)
          )
        )
      },
      div(
        cls := "mt-4",
        div(
          cls := "flex items-center justify-between mb-2",
          span(cls := Form.sectionLabelCls, "File Feed")
//            Buttons.textButton("+ New Feed", Observer[Unit](_ => Nav.toMaintainFileFeed()))
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
      child.maybe <-- saveError.signal.map(
        _.map(err => div(cls := "text-sm text-red-600 dark:text-red-400 mt-4", err))
      ),
      div(
        cls := "flex justify-end gap-2 mt-6",
        Buttons.textButton("Cancel", Observer[Unit](_ => onClose())),
        Buttons.linkButton("Save", Observer[Unit](_ => saveBus.emit(())))
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

  private enum LoadState:
    case Loading
    case Ready(spec: MovementSpecPayload)
