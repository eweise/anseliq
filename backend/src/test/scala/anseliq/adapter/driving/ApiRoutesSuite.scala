package anseliq.adapter.driving

import anseliq.common.http.HttpPath

class ApiRoutesSuite extends munit.FunSuite:
  import ApiRoutes.roleMayCall

  test("a Viewer may read"):
    assert(roleMayCall("Viewer", "GET", HttpPath.ledgers))
    assert(roleMayCall("Viewer", "HEAD", HttpPath.ledgers))

  test("a Viewer may make the POSTs that only read"):
    assert(roleMayCall("Viewer", "POST", s"${HttpPath.movements}/search"))
    assert(roleMayCall("Viewer", "POST", s"${HttpPath.movements}/bad-file-preview"))
    assert(roleMayCall("Viewer", "POST", s"${HttpPath.fileFeed}/0b6c1f9e-2d1a-4c55-9a43-1f7a3b2c9d10/test-parse"))

  test("a Viewer may not write"):
    assert(!roleMayCall("Viewer", "POST", HttpPath.ledger))
    assert(!roleMayCall("Viewer", "PUT", s"${HttpPath.ledger}/some-id"))
    assert(!roleMayCall("Viewer", "DELETE", s"${HttpPath.fileFeed}/some-id"))
    assert(!roleMayCall("Viewer", "POST", s"${HttpPath.reconciliation}/some-id/write-off"))
    assert(!roleMayCall("Viewer", "POST", s"${HttpPath.movements}/bad-file-fix"))
    assert(!roleMayCall("Viewer", "POST", s"${HttpPath.exception}/some-id/comments"))

  test("a test-parse lookalike outside file feeds isn't treated as a read"):
    assert(!roleMayCall("Viewer", "POST", s"${HttpPath.movement}/some-id/test-parse"))

  test("Analysts and Admins aren't limited here"):
    assert(roleMayCall("Analyst", "POST", s"${HttpPath.reconciliation}/some-id/write-off"))
    assert(roleMayCall("Admin", "DELETE", s"${HttpPath.fileFeed}/some-id"))
