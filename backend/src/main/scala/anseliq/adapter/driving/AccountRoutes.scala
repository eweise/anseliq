package anseliq.adapter.driving

import anseliq.common.http.HttpPath
import anseliq.common.model.*
import anseliq.domain.model.*
import anseliq.domain.model.helper.JSONBProps
import anseliq.domain.port.driven.*
import anseliq.domain.service.*
import com.augustnagro.magnum.*
import upickle.default.*

import java.util.UUID

class AccountRoutes(
  transactor:     Transactor,
  accountRepo:    AccountRepo,
  accountService: AccountService
) extends ApiRoutes:

  @cask.route(s"${HttpPath.fundFlows}/:fundFlowID/accounts", methods = HttpCmd.GET)
  def accountsByFundFlowID(fundFlowID: String): String =
    transact(transactor):
      val accounts = accountRepo.findByFundFlow(UUID.fromString(fundFlowID))
      val payload  = accounts.map(Payload.accountPayloadFromDomain)
      write(payload)

  @cask.route(HttpPath.accounts, methods = HttpCmd.GET)
  def allAccounts(): String =
    transact(transactor):
      val accounts = accountRepo.findAll()
      val payload  = accounts.map(Payload.accountPayloadFromDomain)
      write(payload)

  // methods = Seq("get"), not HttpCmd.GET: updateAccount (PUT, below) shares this same path and
  // already registers "options" there via HttpCmd.PUT — cask allows only one "options" registration
  // per path, same reasoning as listUsers/createUser in UserRoutes.
  @cask.route(s"${HttpPath.account}/:id", methods = Seq("get"))
  def getAccount(id: String, request: cask.Request): String =
    transact(transactor):
      write(accountRepo.findById(UUID.fromString(id)).map(Payload.accountPayloadFromDomain))

  @cask.route(s"${HttpPath.account}", methods = HttpCmd.POST)
  def createAccount(request: cask.Request): String =
    val accountRequest = read[AccountPayload](request.text())
    transact(transactor):
      write(
        Payload.accountPayloadFromDomain(accountService.createAccount(Payload.accountPayloadToDomain(accountRequest)))
      )

  @cask.route(s"${HttpPath.account}/:id", methods = HttpCmd.PUT)
  def updateAccount(id: String, request: cask.Request): String =
    val accountRequest = read[AccountPayload](request.text())
    if id.trim == "" then throw IllegalArgumentException("Account ID is required")
    val accountID = UUID.fromString(id)
    transact(transactor):
      val updated = accountService.updateAccount(
        accountID,
        name        = accountRequest.name,
        accountType = Payload.accountTypeToDomain(accountRequest.accountType),
        properties  = JSONBProps(accountRequest.properties)
      )
      write(Payload.accountPayloadFromDomain(updated))

  initialize()
