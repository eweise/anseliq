package onparity.view

import onparity.common.model.{AccountPayload, AccountTypePayload}

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object ViewHelper:
  private val customFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

  def formatDateTime(dt: OffsetDateTime): String =
    dt.format(customFormatter)

  // The standard fund flow setup: a Primary account settles OUT to Recon, and Recon settles OUT to
  // a Secondary account — so Recon receives from Primary and pays out to Secondary, netting to zero
  // when the amounts match. Returns (outAccountId, inAccountId) for a MovementSpec linking the two.
  def accountLinkFields(nonRecon: AccountPayload, recon: AccountPayload): (String, String) =
    if nonRecon.accountType == AccountTypePayload.Primary then (nonRecon.id.getOrElse(""), recon.id.getOrElse(""))
    else (recon.id.getOrElse(""), nonRecon.id.getOrElse(""))
