package anseliq.adapter.driven

import com.typesafe.config.ConfigFactory
import software.amazon.awssdk.auth.credentials.{DefaultCredentialsProvider, StaticCredentialsProvider}

import java.net.URI

class S3FileSourceFactorySuite extends munit.FunSuite:
  private def factory(settings: String) =
    S3FileSourceFactory.fromConfig(ConfigFactory.parseString(s"s3.region = us-east-1\n$settings"))

  test("a key and an endpoint give LocalStack-style access with that key"):
    val s3 = factory(
      """s3.endpoint = "http://localhost:4566"
        |s3.username = test
        |s3.password = test""".stripMargin
    )
    val config = s3.s3Client.serviceClientConfiguration()
    assert(config.credentialsProvider().isInstanceOf[StaticCredentialsProvider])
    assertEquals(config.endpointOverride().orElse(null), URI.create("http://localhost:4566"))

  // These check the factory's own endpoint, not the client's: with none set, the SDK still takes
  // one from AWS_ENDPOINT_URL or the AWS profile, as a developer's machine may have for LocalStack.
  test("with no key or endpoint it uses real S3 and the default credentials chain"):
    val s3 = factory("")
    assert(s3.s3Client.serviceClientConfiguration().credentialsProvider().isInstanceOf[DefaultCredentialsProvider])
    assertEquals(s3.endpoint, None)

  test("empty settings count as unset"):
    val s3 = factory("""s3.endpoint = "", s3.username = "", s3.password = """"")
    assert(s3.s3Client.serviceClientConfiguration().credentialsProvider().isInstanceOf[DefaultCredentialsProvider])
    assertEquals(s3.endpoint, None)

  test("a key ID without a secret is refused"):
    intercept[IllegalStateException](factory("s3.username = test"))
