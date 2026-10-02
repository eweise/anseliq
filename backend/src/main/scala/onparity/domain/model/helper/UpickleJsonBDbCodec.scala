package onparity.domain.model.helper

import com.augustnagro.magnum.pg.json.JsonBDbCodec
import upickle.default.ReadWriter

// For jsonb columns holding types defined in `common` (which uses upickle, not circe, and has no
// Magnum dependency, so can't derive CirceJsonBDbCodec itself).
def upickleJsonBDbCodec[A: ReadWriter]: JsonBDbCodec[A] = new:
  def encode(a: A): String = upickle.default.write(a)

  def decode(json: String): A = upickle.default.read[A](json)
