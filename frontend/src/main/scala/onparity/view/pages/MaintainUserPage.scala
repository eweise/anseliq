package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.{CreateUserRequest, ResetPasswordRequest, UpdateUserRequest, UserPayload}
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.SessionStore
import onparity.view.components.{Buttons, Form}
import upickle.default.write

object MaintainUserPage:

  private val roleOptions = Seq("Admin" -> "Admin", "Analyst" -> "Analyst", "Viewer" -> "Viewer")
  // Mirrors UserService's own minPasswordLength — client-side check so the common case (someone
  // just types a short password) never round-trips to the backend at all, rather than relying on
  // the backend's error to surface something usable.
  private val minPasswordLength = 8

  case class State(
    id:          Option[String] = None,
    email:       String         = "",
    firstName:   String         = "",
    lastName:    String         = "",
    role:        String         = "Viewer",
    active:      Boolean        = true,
    password:    String         = "",
    newPassword: String         = "",
    saveError:   Option[String] = None,
    resetStatus: Option[String] = None
  )

  // Backend errors surface as an uncaught exception's raw message + stack trace in the response
  // body (an app-wide characteristic of this backend's error handling, not something to work around
  // here) — take just the first line so a save/reset failure shows one readable sentence instead of
  // a multi-page trace.
  private def firstLine(message: String): String = message.linesIterator.nextOption().getOrElse(message)

  // The form is built ONCE below, not inside a `child <-- state.signal.map(...)` — wrapping the
  // whole form in a reactive rebuild (as an earlier version of this page did) destroys and
  // recreates every input on every keystroke, since typing itself triggers a state update, which
  // loses focus/cursor position after every character. Fields that need to reflect data loaded
  // asynchronously (editing an existing user) are instead rebound in place via each field's own
  // `updates` stream, the same pattern Form.field/Form.tableInput already use elsewhere in this app.
  // Rendered inside Modal by UsersPage (its only caller) — onClose defaults to Nav.back() so this
  // still works if ever reached directly via its route (/components/maintain-user/:id).
  def apply(userId: String, onClose: () => Unit = () => Nav.back()): HtmlElement =
    val isNew    = userId == "new"
    val state    = Var(State(id = if isNew then None else Some(userId)))
    val saveBus  = EventBus[Unit]()
    val resetBus = EventBus[Unit]()

    val firstNameBus   = EventBus[String]()
    val lastNameBus    = EventBus[String]()
    val newPasswordBus = EventBus[String]()

    div(
      // Triggers the load as part of this stream's own subscription (fires once, as soon as this
      // modifier is mounted) rather than via a separate onMountCallback emitting into an EventBus
      // that this stream listens to — two separate modifiers on the same element race on mount
      // order, and if the onMountCallback's emit fires before this stream's subscription is wired
      // up, EventBus drops it (no replay to late subscribers), silently skipping the load entirely.
      (if isNew then EventStream.empty else EventStream.fromValue(())).flatMapSwitch { _ =>
        ApiClient.get[Seq[UserPayload]](HttpPath.url(HttpPath.users), SessionStore.token)
      }.apiBind { users =>
        users.find(_.id.contains(userId)).foreach { u =>
          state.update(
            _.copy(email = u.email, firstName = u.firstName, lastName = u.lastName, role = u.role, active = u.active)
          )
          firstNameBus.emit(u.firstName)
          lastNameBus.emit(u.lastName)
        }
      },
      // Not .apiBind: a validation failure here is an expected, user-actionable outcome (bad
      // password, duplicate email), not a global/unexpected error — shown inline on the form
      // instead of the app-wide error banner, same reasoning LoginPage's own inline error uses.
      saveBus.events.flatMapSwitch { _ =>
        val s     = state.now()
        val token = SessionStore.token
        s.id match
          case Some(id) =>
            ApiClient.put[UserPayload, UpdateUserRequest](
              HttpPath.url(s"${HttpPath.users}/$id"),
              UpdateUserRequest(s.firstName, s.lastName, s.role, s.active),
              token
            )
          case None =>
            ApiClient.post[UserPayload, CreateUserRequest](
              HttpPath.url(HttpPath.users),
              CreateUserRequest(s.email, s.firstName, s.lastName, s.role, s.password),
              token
            )
      } --> {
        case Right(_)  => onClose()
        case Left(err) => state.update(_.copy(saveError = Some(firstLine(err))))
      },
      resetBus.events.flatMapSwitch { _ =>
        val s = state.now()
        ApiClient.postRaw(
          HttpPath.url(s"${HttpPath.users}/${s.id.getOrElse("")}/reset-password"),
          write(ResetPasswordRequest(s.newPassword)),
          SessionStore.token
        )
      } --> {
        case Right(_) =>
          state.update(_.copy(newPassword = "", resetStatus = Some("Password reset.")))
          newPasswordBus.emit("")
        case Left(err) => state.update(_.copy(resetStatus = Some(s"Failed: ${firstLine(err)}")))
      },
      h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-7", "User"),
      // autoCompleteHint = "off"/"new-password": without these, the browser's saved-credential
      // autofill treats this email+password pair as a login form and silently fills in the admin's
      // own saved onparity credentials rather than leaving a blank field to create a new user with.
      if isNew then
        Form.field("email", "Email", "", v => state.update(_.copy(email = v)), autoCompleteHint = Some("off"))
      else
        div(
          cls := "mb-5 text-sm text-md-on-surface-variant dark:text-gray-400",
          child.text <-- state.signal.map(s => s"Email: ${s.email}")
        )
      ,
      Form.field(
        "firstName",
        "First Name",
        "",
        v => state.update(_.copy(firstName = v)),
        updates = firstNameBus.events
      ),
      Form.field("lastName", "Last Name", "", v => state.update(_.copy(lastName = v)), updates = lastNameBus.events),
      // Reactively rebuilt on role change, unlike the text fields above: a <select> is a
      // discrete click-to-choose control with no cursor/focus position to lose, so a full
      // rebuild on load is safe here — no need for the same bus-based in-place rebind the text
      // fields use to avoid disrupting mid-typing focus.
      child <-- state.signal
        .map(_.role)
        .map(role => Form.selectField("role", "Role", roleOptions, role, v => state.update(_.copy(role = v)))),
      if isNew then
        Form.field(
          "password",
          "Password",
          "",
          v => state.update(_.copy(password = v)),
          inputType        = "password",
          autoCompleteHint = Some("new-password")
        )
      else
        div(
          cls := "flex items-center gap-2 mb-5 cursor-pointer",
          onClick --> (_ => state.update(st => st.copy(active = !st.active))),
          div(
            cls <-- state.signal.map(s =>
              s"w-4 h-4 rounded border flex items-center justify-center ${
                  if s.active then "bg-md-primary border-md-primary" else "border-gray-400"
                }"
            ),
            child <-- state.signal.map(s =>
              if s.active then span(cls := "text-white text-xs leading-none", "✓") else span()
            )
          ),
          span(cls := "text-sm text-md-on-surface dark:text-gray-200", "Active")
        )
      ,
      if isNew then div()
      else
        div(
          cls := "border-t border-md-outline-variant dark:border-gray-700 pt-4 mt-2",
          span(
            cls := "text-xs font-semibold uppercase tracking-widest text-md-on-surface-variant dark:text-gray-400",
            "Reset Password"
          ),
          div(
            cls := "mt-2",
            Form.field(
              "newPassword",
              "New Password",
              "",
              v => state.update(_.copy(newPassword = v)),
              inputType        = "password",
              updates          = newPasswordBus.events,
              autoCompleteHint = Some("new-password")
            )
          ),
          div(
            cls := "flex items-center gap-3",
            Buttons.textButton(
              "Reset Password",
              Observer[Unit](_ =>
                if state.now().newPassword.length < minPasswordLength then
                  state.update(_.copy(resetStatus = Some(s"Password must be at least $minPasswordLength characters")))
                else resetBus.emit(())
              )
            ),
            child.maybe <-- state.signal.map(
              _.resetStatus.map(msg => span(cls := "text-xs text-md-on-surface-variant dark:text-gray-400", msg))
            )
          )
        )
      ,
      child.maybe <-- state.signal.map(
        _.saveError.map(msg => div(cls := "text-sm text-red-600 dark:text-red-400 mb-2", msg))
      ),
      div(
        cls := "flex justify-end gap-2 mt-6",
        Buttons.textButton("Cancel", Observer[Unit](_ => onClose())),
        Buttons.linkButton(
          "Save",
          Observer[Unit](_ =>
            val s = state.now()
            if isNew && s.password.length < minPasswordLength then
              state.update(_.copy(saveError = Some(s"Password must be at least $minPasswordLength characters")))
            else
              state.update(_.copy(saveError = None))
              saveBus.emit(())
          )
        )
      )
    )
